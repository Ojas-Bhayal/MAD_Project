package com.cropdoc.app.ml.policy;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ml.ClassificationOutcome;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.LeafGate;
import com.cropdoc.app.ml.LeafPreprocessor;
import com.cropdoc.app.ml.ModelConfig;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * End-to-end fail-closed safety pipeline connecting raw image preprocessing, image-driven gating,
 * model inference, the inference decision policy, and domain scan persistence / advice lookup.
 *
 * Guarantees that:
 * 1. Non-leaf images never produce a saved scan record or advice.
 * 2. Unsupported crops are rejected and never produce a disease diagnosis.
 * 3. Ambiguous top-2 predictions return inconclusive outcomes with zero persistence.
 * 4. Healthy crops never produce disease-specific treatment or chemical advice.
 * 5. Failed gates, missing models, and corrupt tensors fail closed safely.
 */
public final class EndToEndSafetyPipeline {

    public interface ModelRunner {
        @NonNull
        float[] runInference(@NonNull ByteBuffer inputTensor) throws Exception;
    }

    public static final class PipelineOutput {
        @NonNull private final InferenceDecisionResult decisionResult;
        @NonNull private final ClassificationOutcome classificationOutcome;
        @Nullable private final ScanRecord scanRecord;
        @Nullable private final CropDiseaseAdvice advice;

        public PipelineOutput(
                @NonNull InferenceDecisionResult decisionResult,
                @NonNull ClassificationOutcome classificationOutcome,
                @Nullable ScanRecord scanRecord,
                @Nullable CropDiseaseAdvice advice) {
            this.decisionResult = Objects.requireNonNull(decisionResult, "decisionResult");
            this.classificationOutcome = Objects.requireNonNull(classificationOutcome, "classificationOutcome");
            this.scanRecord = scanRecord;
            this.advice = advice;
        }

        @NonNull public InferenceDecisionResult getDecisionResult() { return decisionResult; }
        @NonNull public ClassificationOutcome getClassificationOutcome() { return classificationOutcome; }
        @Nullable public ScanRecord getScanRecord() { return scanRecord; }
        @Nullable public CropDiseaseAdvice getAdvice() { return advice; }
    }

    @NonNull private final ImageQualityGate qualityGate;
    @NonNull private final InferenceDecisionPolicy policy;
    @NonNull private final AdviceRepository adviceRepository;
    @NonNull private final ScanRepository scanRepository;
    @NonNull private final AuthRepository authRepository;

    public EndToEndSafetyPipeline(
            @NonNull ImageQualityGate qualityGate,
            @NonNull InferenceDecisionPolicy policy,
            @NonNull AdviceRepository adviceRepository,
            @NonNull ScanRepository scanRepository,
            @NonNull AuthRepository authRepository) {
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.adviceRepository = Objects.requireNonNull(adviceRepository, "adviceRepository");
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
    }

    @NonNull
    public PipelineOutput execute(
            @NonNull Bitmap inputBitmap,
            @NonNull ByteBuffer inputBuffer,
            @Nullable LeafGate leafGate,
            @Nullable ModelRunner modelRunner,
            @Nullable String cropContext,
            @NonNull String savedImagePath,
            @NonNull String languageCode) {
        return execute(
                inputBitmap, inputBuffer, leafGate, null, modelRunner,
                cropContext, savedImagePath, languageCode);
    }

    @NonNull
    public PipelineOutput execute(
            @NonNull Bitmap inputBitmap,
            @NonNull ByteBuffer inputBuffer,
            @Nullable LeafGate leafGate,
            @Nullable CropDomainGate cropDomainGate,
            @Nullable ModelRunner modelRunner,
            @Nullable String cropContext,
            @NonNull String savedImagePath,
            @NonNull String languageCode) {

        PipelineOutput qualityFailure = evaluateQualityCheck(inputBitmap, inputBuffer);
        if (qualityFailure != null) {
            return qualityFailure;
        }

        float leafRatio = LeafPreprocessor.populateByteBufferAndComputeLeafRatio(
                inputBitmap, inputBuffer, new int[ModelConfig.PIXEL_COUNT]);
        if (leafRatio < ModelConfig.MIN_LEAF_PIXEL_RATIO) {
            return buildFoliarRejectionOutput(leafRatio);
        }

        LeafGateValidationStatus gateStatus = evaluateLeafGateStatus(leafGate, inputBitmap, inputBuffer);
        CropDomainResult domainResult = evaluateCropDomainResult(cropDomainGate, inputBitmap, inputBuffer);

        PipelineOutput domainEnforcement = evaluateCropDomainGateEnforcement(domainResult);
        if (domainEnforcement != null) {
            return domainEnforcement;
        }

        float[] logits = runModelSafely(modelRunner, inputBuffer);
        boolean modelAvailable = (modelRunner != null && logits != null);

        InferenceDecisionRequest request = new InferenceDecisionRequest.Builder()
                .setRawOutput(logits)
                .setIsLogits(true)
                .setModelAvailable(modelAvailable)
                .setLeafGateStatus(gateStatus)
                .setCropDomainResult(domainResult)
                .setCropContext(cropContext)
                .build();

        InferenceDecisionResult decisionResult = policy.evaluate(request);
        if (!decisionResult.isAccepted()) {
            ClassificationOutcome outcome = mapDecisionToClassificationOutcome(decisionResult);
            return new PipelineOutput(decisionResult, outcome, null, null);
        }

        return buildAcceptedPipelineOutput(decisionResult, leafRatio, savedImagePath, languageCode);
    }

    @Nullable
    private PipelineOutput evaluateQualityCheck(
            @NonNull Bitmap inputBitmap,
            @NonNull ByteBuffer inputBuffer) {
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(inputBitmap, inputBuffer);
        if (quality.isAccepted()) {
            return null;
        }
        ClassificationOutcome.RejectionReason reason = quality.getRejectionReason() != null
                ? quality.getRejectionReason()
                : ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED;
        ClassificationOutcome outcome = new ClassificationOutcome.Rejected(reason);
        InferenceDecisionResult decision = new InferenceDecisionResult(
                PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                PolicyRejectionDetail.OUTPUT_NULL_OR_EMPTY,
                -1, -1, null, null, false, 0f, 0f, 0f,
                quality.getExplanation());
        return new PipelineOutput(decision, outcome, null, null);
    }

    @NonNull
    private static PipelineOutput buildFoliarRejectionOutput(float leafRatio) {
        InferenceDecisionResult decision = new InferenceDecisionResult(
                PolicyOutcomeType.OUT_OF_DOMAIN,
                PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                -1, -1, null, null, false, 0f, 0f, 0f,
                "Image foliar pixel ratio (" + leafRatio + ") is below minimum required 18%.");
        ClassificationOutcome outcome = new ClassificationOutcome.Rejected(
                ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED);
        return new PipelineOutput(decision, outcome, null, null);
    }

    @NonNull
    private static LeafGateValidationStatus evaluateLeafGateStatus(
            @Nullable LeafGate leafGate,
            @NonNull Bitmap inputBitmap,
            @NonNull ByteBuffer inputBuffer) {
        if (leafGate == null) {
            return LeafGateValidationStatus.notPresent();
        }
        if (leafGate.getState() != LeafGate.GateState.VALIDATED) {
            return LeafGateValidationStatus.unvalidated(
                    "Leaf gate is present but unvalidated (state=" + leafGate.getState() + ").");
        }
        LeafGate.GateResult gateRes = leafGate.evaluate(inputBitmap, inputBuffer);
        return LeafGateValidationStatus.fromGate(leafGate, gateRes);
    }

    @NonNull
    private static CropDomainResult evaluateCropDomainResult(
            @Nullable CropDomainGate cropDomainGate,
            @NonNull Bitmap inputBitmap,
            @NonNull ByteBuffer inputBuffer) {
        if (cropDomainGate == null) {
            return CropDomainResult.notPresent();
        }
        if (cropDomainGate.getState() != CropDomainGate.GateState.VALIDATED) {
            return CropDomainResult.unvalidated(
                    "Crop domain gate is present but unvalidated (state=" + cropDomainGate.getState() + ").");
        }
        return cropDomainGate.evaluate(inputBitmap, inputBuffer);
    }

    @Nullable
    private static float[] runModelSafely(
            @Nullable ModelRunner modelRunner,
            @NonNull ByteBuffer inputBuffer) {
        if (modelRunner == null) {
            return null;
        }
        try {
            inputBuffer.rewind();
            return modelRunner.runInference(inputBuffer);
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    private PipelineOutput buildAcceptedPipelineOutput(
            @NonNull InferenceDecisionResult decisionResult,
            float leafRatio,
            @NonNull String savedImagePath,
            @NonNull String languageCode) {
        String classId = decisionResult.getClassId() != null ? decisionResult.getClassId() : "unknown";
        String cropId = decisionResult.getCrop() != null ? decisionResult.getCrop() : "unknown";
        DiagnosisOutcome domainOutcome = decisionResult.isHealthy()
                ? DiagnosisOutcome.HEALTHY_LEAF
                : DiagnosisOutcome.CONFIDENT_DISEASE;

        ClassificationOutcome.Diagnosis diagnosisOutcome = new ClassificationOutcome.Diagnosis(
                classId,
                decisionResult.getConfidence(),
                "2.0.0-research",
                decisionResult.getNormalizedEntropy(),
                15L,
                leafRatio);

        DiagnosisResult domainDiagnosis = new DiagnosisResult(
                classId,
                cropId,
                decisionResult.getConfidence(),
                decisionResult.getNormalizedEntropy(),
                leafRatio,
                15L,
                "2.0.0-research",
                domainOutcome);

        String userId = authRepository.getCurrentUserId();
        ScanRecord savedRecord = scanRepository.saveScanSync(userId, domainDiagnosis, savedImagePath);

        CropDiseaseAdvice advice = null;
        if (decisionResult.isDiseaseDiagnosis()) {
            advice = adviceRepository.getAdviceSync(classId, languageCode);
        }

        return new PipelineOutput(decisionResult, diagnosisOutcome, savedRecord, advice);
    }

    @NonNull
    private static ClassificationOutcome mapDecisionToClassificationOutcome(
            @NonNull InferenceDecisionResult decision) {
        switch (decision.getOutcomeType()) {
            case MODEL_UNAVAILABLE:
                return new ClassificationOutcome.ModelUnavailable(
                        decision.getRejectionDetail() == PolicyRejectionDetail.LEAF_GATE_UNVALIDATED
                                || decision.getRejectionDetail() == PolicyRejectionDetail.CROP_DOMAIN_GATE_UNVALIDATED
                                ? ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED
                                : ClassificationOutcome.UnavailableReason.GATE_UNAVAILABLE);
            case OUT_OF_DOMAIN:
                return new ClassificationOutcome.Rejected(
                        decision.getRejectionDetail() == PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF
                                ? ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED
                                : ClassificationOutcome.RejectionReason.NON_LEAF_CLASS);
            case INVALID_MODEL_OUTPUT:
                return new ClassificationOutcome.ModelUnavailable(
                        ClassificationOutcome.UnavailableReason.UNIFORM_OR_DEGENERATE_OUTPUT);
            case INCONCLUSIVE_LOW_CONFIDENCE:
            default:
                ClassificationOutcome.LowConfidenceReason reason =
                        decision.getRejectionDetail() == PolicyRejectionDetail.MARGIN_BELOW_THRESHOLD
                                ? ClassificationOutcome.LowConfidenceReason.CLOSE_TOP_MARGIN
                                : ClassificationOutcome.LowConfidenceReason.CONFIDENCE_BELOW_THRESHOLD;
                return new ClassificationOutcome.LowConfidence(
                        java.util.Collections.emptyList(),
                        reason);
        }
    }

    @Nullable
    private PipelineOutput evaluateCropDomainGateEnforcement(@Nullable CropDomainResult domainResult) {
        if (!policy.getConfig().isEnforceCropDomainGate()) { return null; }
        if (domainResult == null || domainResult.getStatus() == CropDomainResult.Status.GATE_NOT_PRESENT) {
            return new PipelineOutput(new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE, PolicyRejectionDetail.CROP_DOMAIN_GATE_NOT_PRESENT,
                    -1, -1, null, null, false, 0f, 0f, 0f, "Crop domain gate is not present. Fails closed."),
                    new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.GATE_UNAVAILABLE),
                    null, null);
        }
        if (!domainResult.isGateCertified()) {
            return new PipelineOutput(new InferenceDecisionResult(
                    PolicyOutcomeType.MODEL_UNAVAILABLE, PolicyRejectionDetail.CROP_DOMAIN_GATE_UNVALIDATED,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain gate is unvalidated or failed: " + domainResult.getExplanation()),
                    new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED),
                    null, null);
        }
        if (domainResult.isRejectedUnsupported()) {
            return new PipelineOutput(new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN, PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Image rejected by crop domain gate as unsupported agricultural crop: "
                            + domainResult.getExplanation()),
                    new ClassificationOutcome.Rejected(ClassificationOutcome.RejectionReason.NON_LEAF_CLASS),
                    null, null);
        }
        if (domainResult.isUncertain()) {
            return new PipelineOutput(new InferenceDecisionResult(
                    PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE, PolicyRejectionDetail.CROP_DOMAIN_UNCERTAIN,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Crop domain classification is ambiguous or uncertain: " + domainResult.getExplanation()),
                    new ClassificationOutcome.LowConfidence(java.util.Collections.emptyList(),
                            ClassificationOutcome.LowConfidenceReason.CONFIDENCE_BELOW_THRESHOLD),
                    null, null);
        }
        return null;
    }
}
