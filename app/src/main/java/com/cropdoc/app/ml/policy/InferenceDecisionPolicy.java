package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Fail-closed inference decision layer for leaf disease classification.
 *
 * Implements strict multi-stage safety verification:
 * 1. Model Availability & Gate Certification Verification
 * 2. Structural & Numerical Tensor Output Integrity (NaN/Inf, shape, degenerate)
 * 3. Semantic Leaf Gate Gating (rejects non-leaf, soil, hands, background)
 * 4. Crop Domain Gating (rejects unsupported crops like cotton, cassava, maize)
 * 5. Distributional Calibration & Margin Verification (rejects low confidence, ambiguous top-2 margin)
 *
 * Prevents unsupported crops or non-leaves from ever being accepted as a disease diagnosis
 * merely because a closed-world softmax produced high confidence.
 */
public final class InferenceDecisionPolicy {

    private static final float EPSILON = 1e-12f;
    private static final float PROBABILITY_SUM_TOLERANCE = 0.05f;

    @NonNull private final InferencePolicyConfig config;

    public InferenceDecisionPolicy(@NonNull InferencePolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public InferenceDecisionPolicy() {
        this(InferencePolicyConfig.createCalibrated());
    }

    @NonNull
    public InferencePolicyConfig getConfig() {
        return config;
    }

    @NonNull
    public InferenceDecisionResult evaluate(@NonNull InferenceDecisionRequest request) {
        Objects.requireNonNull(request, "request");

        InferenceDecisionResult integrityResult = verifyRawOutputIntegrity(request);
        if (integrityResult != null) {
            return integrityResult;
        }
        float[] raw = Objects.requireNonNull(request.getRawOutput());

        float[] probs = request.isLogits()
                ? computeCalibratedSoftmax(raw, config.getTemperature())
                : raw.clone();
        if (!request.isLogits()) {
            InferenceDecisionResult probError = validateProbabilityDistribution(probs);
            if (probError != null) {
                return probError;
            }
        }

        InferenceDecisionResult gateFailure = validateSafetyGates(request);
        if (gateFailure != null) {
            return gateFailure;
        }

        DistributionMetrics metrics = analyzeDistribution(probs);
        SixClassResearchMapping.ClassEntry classEntry = SixClassResearchMapping.getByLocalIndex(metrics.top1Idx);
        String classId = classEntry != null ? classEntry.classId : "unknown";
        String crop = classEntry != null ? classEntry.crop : "unknown";
        int globalIdx = classEntry != null ? classEntry.globalIndex : -1;
        boolean isHealthy = classEntry != null && classEntry.isHealthy;

        InferenceDecisionResult crossDomainFailure = verifyCropDomainCrossConsistency(
                request.getCropDomainResult(), crop, classId, metrics.top1Idx, globalIdx,
                isHealthy, metrics.top1Prob, metrics.margin, metrics.normalizedEntropy);
        if (crossDomainFailure != null) {
            return crossDomainFailure;
        }

        return evaluateQualityThresholds(metrics, classId, crop, globalIdx, isHealthy);
    }

    @Nullable
    private static InferenceDecisionResult validateProbabilityDistribution(@NonNull float[] probs) {
        float sum = 0f;
        for (float p : probs) {
            if (p < -1e-5f || p > 1.0f + 1e-5f) {
                return new InferenceDecisionResult(
                        PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                        PolicyRejectionDetail.INVALID_PROBABILITY_DISTRIBUTION,
                        -1, -1, null, null, false, 0f, 0f, 0f,
                        "Probability values out of valid [0, 1] range: " + p);
            }
            sum += p;
        }
        if (Math.abs(sum - 1.0f) > PROBABILITY_SUM_TOLERANCE) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    PolicyRejectionDetail.INVALID_PROBABILITY_DISTRIBUTION,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Probabilities do not sum to 1.0 (actual sum: " + sum + ").");
        }
        return null;
    }

    @Nullable
    private InferenceDecisionResult validateSafetyGates(@NonNull InferenceDecisionRequest request) {
        if (config.isEnforceLeafGate()) {
            InferenceDecisionResult leafGateErr = validateLeafGateStatus(request.getLeafGateStatus());
            if (leafGateErr != null) {
                return leafGateErr;
            }
        }
        if (config.isEnforceCropDomain()) {
            InferenceDecisionResult cropErr = validateCropContext(request.getCropContext());
            if (cropErr != null) {
                return cropErr;
            }
        }
        if (config.isEnforceCropDomainGate()) {
            InferenceDecisionResult gateErr = validateCropDomainGate(request.getCropDomainResult());
            if (gateErr != null) {
                return gateErr;
            }
        }
        Float oodScore = request.getOodScore();
        if (oodScore != null && oodScore > 0.50f) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN,
                    PolicyRejectionDetail.OOD_SCORE_EXCEEDED,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "OOD detector score (" + oodScore + ") exceeded rejection threshold.");
        }
        return null;
    }

    @Nullable
    private static InferenceDecisionResult validateLeafGateStatus(@Nullable LeafGateValidationStatus gateStatus) {
        if (gateStatus == null || !gateStatus.isGatePresent()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE,
                    PolicyRejectionDetail.LEAF_GATE_NOT_PRESENT,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Leaf gate is not present. Fails closed.");
        }
        if (!gateStatus.isGateCertified()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE,
                    PolicyRejectionDetail.LEAF_GATE_UNVALIDATED,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Leaf gate is present but unvalidated. Fails closed: " + gateStatus.getExplanation());
        }
        if (gateStatus.getDecision() != com.cropdoc.app.ml.LeafGate.GateDecision.ACCEPT) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN,
                    PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Image rejected by leaf quality/semantic gate as non-leaf: " + gateStatus.getExplanation());
        }
        return null;
    }

    @Nullable
    private static InferenceDecisionResult validateCropContext(@Nullable String cropContext) {
        if (cropContext == null || !SixClassResearchMapping.isSupportedCrop(cropContext)) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN,
                    PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain '" + cropContext + "' is unsupported. Supported crops: "
                            + SixClassResearchMapping.SUPPORTED_CROPS + ".");
        }
        return null;
    }

    @Nullable
    private static InferenceDecisionResult validateCropDomainGate(@Nullable CropDomainResult domainResult) {
        if (domainResult == null || domainResult.getStatus() == CropDomainResult.Status.GATE_NOT_PRESENT) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE,
                    PolicyRejectionDetail.CROP_DOMAIN_GATE_NOT_PRESENT,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain gate is not present. Fails closed.");
        }
        if (!domainResult.isGateCertified()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE,
                    PolicyRejectionDetail.CROP_DOMAIN_GATE_UNVALIDATED,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain gate is unvalidated or failed: " + domainResult.getExplanation());
        }
        if (domainResult.isRejectedUnsupported()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN,
                    PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Image rejected by crop domain gate as unsupported agricultural crop: "
                            + domainResult.getExplanation());
        }
        if (domainResult.isUncertain()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                    PolicyRejectionDetail.CROP_DOMAIN_UNCERTAIN,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain classification is ambiguous or uncertain: " + domainResult.getExplanation());
        }
        return null;
    }

    private static final class DistributionMetrics {
        final int top1Idx;
        final float top1Prob;
        final float margin;
        final float normalizedEntropy;

        DistributionMetrics(int top1Idx, float top1Prob, float margin, float normalizedEntropy) {
            this.top1Idx = top1Idx;
            this.top1Prob = top1Prob;
            this.margin = margin;
            this.normalizedEntropy = normalizedEntropy;
        }
    }

    @NonNull
    private static DistributionMetrics analyzeDistribution(@NonNull float[] probs) {
        int top1Idx = probs[1] > probs[0] ? 1 : 0;
        int top2Idx = probs[1] > probs[0] ? 0 : 1;
        float top1Prob = probs[top1Idx];
        float top2Prob = probs[top2Idx];

        for (int i = 2; i < probs.length; i++) {
            float p = probs[i];
            if (p > top1Prob) {
                top2Prob = top1Prob;
                top2Idx = top1Idx;
                top1Prob = p;
                top1Idx = i;
            } else if (p > top2Prob) {
                top2Prob = p;
                top2Idx = i;
            }
        }
        float margin = top1Prob - top2Prob;
        float normalizedEntropy = computeNormalizedEntropy(probs);
        return new DistributionMetrics(top1Idx, top1Prob, margin, normalizedEntropy);
    }

    @NonNull
    private InferenceDecisionResult evaluateQualityThresholds(
            @NonNull DistributionMetrics metrics,
            @NonNull String classId,
            @NonNull String crop,
            int globalIdx,
            boolean isHealthy) {
        if (metrics.margin < config.getMarginThreshold()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                    PolicyRejectionDetail.MARGIN_BELOW_THRESHOLD,
                    metrics.top1Idx, globalIdx, classId, crop, isHealthy,
                    metrics.top1Prob, metrics.margin, metrics.normalizedEntropy,
                    "Top-2 margin (" + metrics.margin + ") is below threshold ("
                            + config.getMarginThreshold() + "). Prediction is ambiguous.");
        }
        if (metrics.top1Prob < config.getConfidenceThreshold()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                    PolicyRejectionDetail.CONFIDENCE_BELOW_THRESHOLD,
                    metrics.top1Idx, globalIdx, classId, crop, isHealthy,
                    metrics.top1Prob, metrics.margin, metrics.normalizedEntropy,
                    "Top-1 confidence (" + metrics.top1Prob + ") is below threshold ("
                            + config.getConfidenceThreshold() + ").");
        }
        if (metrics.normalizedEntropy > config.getMaxNormalizedEntropy()) {
            return new InferenceDecisionResult(
                    PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE,
                    PolicyRejectionDetail.ENTROPY_ABOVE_THRESHOLD,
                    metrics.top1Idx, globalIdx, classId, crop, isHealthy,
                    metrics.top1Prob, metrics.margin, metrics.normalizedEntropy,
                    "Normalized entropy (" + metrics.normalizedEntropy + ") exceeds threshold ("
                            + config.getMaxNormalizedEntropy() + ").");
        }
        return new InferenceDecisionResult(
                PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS,
                PolicyRejectionDetail.NONE,
                metrics.top1Idx, globalIdx, classId, crop, isHealthy,
                metrics.top1Prob, metrics.margin, metrics.normalizedEntropy,
                "Accepted supported class: " + classId + " (conf=" + metrics.top1Prob
                        + ", margin=" + metrics.margin + ").");
    }

    @NonNull
    public static float[] computeCalibratedSoftmax(@NonNull float[] logits, float temperature) {
        float temp = Math.max(0.01f, temperature);
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float val : logits) {
            float scaled = val / temp;
            if (scaled > maxLogit) {
                maxLogit = scaled;
            }
        }
        float sumExp = 0f;
        float[] expValues = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            float exp = (float) Math.exp((logits[i] / temp) - maxLogit);
            expValues[i] = exp;
            sumExp += exp;
        }
        float[] probabilities = new float[logits.length];
        float safeSum = sumExp > 0f ? sumExp : 1f;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = expValues[i] / safeSum;
        }
        return probabilities;
    }

    public static float computeNormalizedEntropy(@NonNull float[] probabilities) {
        if (probabilities.length <= 1) {
            return 0f;
        }
        double entropy = 0.0;
        for (float p : probabilities) {
            if (p > EPSILON) {
                entropy -= p * Math.log(p);
            }
        }
        double maxEntropy = Math.log(probabilities.length);
        return (float) (maxEntropy > 0.0 ? Math.min(1.0, entropy / maxEntropy) : 0.0);
    }

    @Nullable
    private InferenceDecisionResult verifyRawOutputIntegrity(@NonNull InferenceDecisionRequest request) {
        if (!request.isModelAvailable()) {
            return new InferenceDecisionResult(PolicyOutcomeType.MODEL_UNAVAILABLE,
                    PolicyRejectionDetail.MODEL_NOT_AVAILABLE, -1, -1, null, null, false, 0f, 0f, 0f,
                    "Model is unavailable or uninitialized.");
        }
        float[] raw = request.getRawOutput();
        if (raw == null || raw.length == 0) {
            return new InferenceDecisionResult(PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    PolicyRejectionDetail.OUTPUT_NULL_OR_EMPTY, -1, -1, null, null, false, 0f, 0f, 0f,
                    "Model output array is null or empty.");
        }
        if (raw.length != config.getExpectedClasses()) {
            return new InferenceDecisionResult(PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    PolicyRejectionDetail.INVALID_CLASS_COUNT, -1, -1, null, null, false, 0f, 0f, 0f,
                    "Output dimension " + raw.length + " does not match expected " + config.getExpectedClasses() + ".");
        }
        boolean allEqual = true;
        float firstVal = raw[0];
        for (float val : raw) {
            if (Float.isNaN(val) || Float.isInfinite(val)) {
                return new InferenceDecisionResult(PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                        PolicyRejectionDetail.NON_FINITE_VALUES, -1, -1, null, null, false, 0f, 0f, 0f,
                        "Model output contains NaN or Infinite values.");
            }
            if (Math.abs(val - firstVal) > 1e-6f) {
                allEqual = false;
            }
        }
        if (allEqual) {
            return new InferenceDecisionResult(PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    PolicyRejectionDetail.DEGENERATE_UNIFORM_OUTPUT, -1, -1, null, null, false, 0f, 0f, 0f,
                    "Model output is degenerate or completely uniform.");
        }
        return null;
    }

    @Nullable
    private InferenceDecisionResult verifyCropDomainCrossConsistency(
            @Nullable CropDomainResult domainResult,
            @NonNull String crop,
            @NonNull String classId,
            int top1Idx,
            int globalIdx,
            boolean isHealthy,
            float top1Prob,
            float margin,
            float normalizedEntropy) {
        if (config.isEnforceCropDomainGate() && domainResult != null && domainResult.isAccepted()) {
            boolean isRicePred = "rice".equalsIgnoreCase(crop);
            boolean isGnutPred = "groundnut".equalsIgnoreCase(crop);
            if (domainResult.isRice() && !isRicePred) {
                return new InferenceDecisionResult(
                        PolicyOutcomeType.OUT_OF_DOMAIN,
                        PolicyRejectionDetail.CROP_DOMAIN_MISMATCH,
                        top1Idx, globalIdx, classId, crop, isHealthy,
                        top1Prob, margin, normalizedEntropy,
                        "Crop domain gate detected Rice foliage, but disease model predicted "
                                + crop + " (" + classId + "). Cross-domain mismatch rejected.");
            }
            if (domainResult.isGroundnut() && !isGnutPred) {
                return new InferenceDecisionResult(
                        PolicyOutcomeType.OUT_OF_DOMAIN,
                        PolicyRejectionDetail.CROP_DOMAIN_MISMATCH,
                        top1Idx, globalIdx, classId, crop, isHealthy,
                        top1Prob, margin, normalizedEntropy,
                        "Crop domain gate detected Groundnut foliage, but disease model predicted "
                                + crop + " (" + classId + "). Cross-domain mismatch rejected.");
            }
        }
        return null;
    }
}
