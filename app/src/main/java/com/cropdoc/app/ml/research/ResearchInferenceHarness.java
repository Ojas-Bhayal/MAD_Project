package com.cropdoc.app.ml.research;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.LeafPreprocessor;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.ml.policy.CropDomainResult;
import com.cropdoc.app.ml.policy.InferenceDecisionPolicy;
import com.cropdoc.app.ml.policy.InferenceDecisionRequest;
import com.cropdoc.app.ml.policy.InferenceDecisionResult;
import com.cropdoc.app.ml.policy.LeafGateValidationStatus;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.policy.PolicyRejectionDetail;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Isolated research inference harness coordinating sequential execution of:
 * 1. Image quality & foliar leaf checks
 * 2. Three-output crop-domain gate
 * 3. Six-class research disease classifier
 * 4. Calibrated fail-closed decision policy.
 */
public final class ResearchInferenceHarness {

    private static final float IMAGENET_MEAN_R = 0.485f;
    private static final float IMAGENET_MEAN_G = 0.456f;
    private static final float IMAGENET_MEAN_B = 0.406f;
    private static final float IMAGENET_STD_R = 0.229f;
    private static final float IMAGENET_STD_G = 0.224f;
    private static final float IMAGENET_STD_B = 0.225f;

    @NonNull private final ImageQualityGate qualityGate;
    @NonNull private final InferenceDecisionPolicy policy;
    @Nullable private final ResearchModelExecutor domainExecutor;
    @Nullable private final ResearchModelExecutor diseaseExecutor;

    public ResearchInferenceHarness(
            @NonNull ImageQualityGate qualityGate,
            @NonNull InferenceDecisionPolicy policy,
            @Nullable ResearchModelExecutor domainExecutor,
            @Nullable ResearchModelExecutor diseaseExecutor) {
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.domainExecutor = domainExecutor;
        this.diseaseExecutor = diseaseExecutor;
    }

    @NonNull
    public ResearchHarnessOutcome processImage(
            @NonNull Bitmap bitmap224,
            @NonNull ByteBuffer byteBuffer) {
        // Populate byte buffer and compute foliar coverage
        int[] pixels = new int[ModelConfig.PIXEL_COUNT];
        float leafRatio = LeafPreprocessor.populateByteBufferAndComputeLeafRatio(
                bitmap224, byteBuffer, pixels);

        // Stage 1: Quality verification (exposure and dimensions)
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(bitmap224, byteBuffer);
        if (!quality.isAccepted()) {
            InferenceDecisionResult dec = new InferenceDecisionResult(
                    PolicyOutcomeType.INVALID_MODEL_OUTPUT,
                    PolicyRejectionDetail.OUTPUT_NULL_OR_EMPTY,
                    -1, -1, null, null, false, 0f, 0f, 0f, quality.getExplanation());
            return new ResearchHarnessOutcome(
                    ResearchHarnessOutcome.Stage.QUALITY_CHECK, false, null, 0f, null, 0f, dec,
                    "Rejected by image quality gate: " + quality.getExplanation());
        }

        // Stage 2: Foliar spectral coverage verification
        if (leafRatio < ModelConfig.MIN_LEAF_PIXEL_RATIO) {
            InferenceDecisionResult dec = new InferenceDecisionResult(
                    PolicyOutcomeType.OUT_OF_DOMAIN,
                    PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                    -1, -1, null, null, false, 0f, 0f, 0f,
                    "Foliar coverage ratio (" + leafRatio + ") is below required 18%.");
            return new ResearchHarnessOutcome(
                    ResearchHarnessOutcome.Stage.FOLIAR_GATE, false, null, 0f, null, 0f, dec,
                    "Rejected by foliar coverage gate: non-leaf input.");
        }

        // Stage 3: Prepare normalized float tensor buffer
        ByteBuffer floatBuffer = prepareNormalizedFloatBuffer(pixels);

        // Stage 4: Run Crop-Domain Gate
        CropDomainResult domainResult = evaluateDomainGate(floatBuffer);
        if (!domainResult.isAccepted()) {
            return buildDomainRejectionOutcome(domainResult);
        }

        // Stage 5: Run Six-Class Disease Model & Decision Policy
        return evaluateDiseaseModelAndPolicy(floatBuffer, domainResult);
    }

    @NonNull
    private CropDomainResult evaluateDomainGate(@NonNull ByteBuffer floatBuffer) {
        if (domainExecutor == null || !domainExecutor.isAvailable()) {
            return CropDomainResult.notPresent();
        }
        try {
            floatBuffer.rewind();
            float[] logits = domainExecutor.execute(floatBuffer);
            if (logits.length != 3) {
                return CropDomainResult.unvalidated("Domain gate output length " + logits.length + " != 3");
            }
            return computeDomainSoftmaxAndDecision(logits);
        } catch (Throwable t) {
            return CropDomainResult.error("Domain gate execution failed: " + t.getMessage());
        }
    }

    @NonNull
    private CropDomainResult computeDomainSoftmaxAndDecision(@NonNull float[] logits) {
        float maxVal = Math.max(logits[0], Math.max(logits[1], logits[2]));
        float sum = 0f;
        float[] exp = new float[3];
        for (int i = 0; i < 3; i++) {
            exp[i] = (float) Math.exp(logits[i] - maxVal);
            sum += exp[i];
        }
        float pRice = exp[0] / sum;
        float pGnut = exp[1] / sum;
        float pUnsupp = exp[2] / sum;

        float entropy = 0f;
        for (int i = 0; i < 3; i++) {
            float p = exp[i] / sum;
            if (p > 1e-12f) {
                entropy -= (float) (p * Math.log(p));
            }
        }
        entropy = entropy / (float) Math.log(3.0);

        if (pUnsupp > pRice && pUnsupp > pGnut) {
            return CropDomainResult.rejectedUnsupported(pUnsupp, entropy, "Unsupported crop detected");
        }
        if (Math.max(pRice, pGnut) < 0.65f || entropy > 0.65f) {
            return CropDomainResult.rejectedUncertain(Math.max(pRice, pGnut), entropy, "Uncertain crop domain");
        }
        if (pRice >= pGnut) {
            return CropDomainResult.acceptedRice(pRice, entropy, "Supported rice foliage confirmed");
        }
        return CropDomainResult.acceptedGroundnut(pGnut, entropy, "Supported groundnut foliage confirmed");
    }

    @NonNull
    private ResearchHarnessOutcome buildDomainRejectionOutcome(@NonNull CropDomainResult domainResult) {
        PolicyOutcomeType type = domainResult.isRejectedUnsupported()
                ? PolicyOutcomeType.OUT_OF_DOMAIN
                : (domainResult.isUncertain()
                        ? PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE
                        : PolicyOutcomeType.MODEL_UNAVAILABLE);
        PolicyRejectionDetail detail = domainResult.isRejectedUnsupported()
                ? PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN
                : (domainResult.isUncertain()
                        ? PolicyRejectionDetail.CROP_DOMAIN_UNCERTAIN
                        : PolicyRejectionDetail.CROP_DOMAIN_GATE_NOT_PRESENT);

        InferenceDecisionResult dec = new InferenceDecisionResult(
                type, detail, -1, -1, null, null, false,
                domainResult.getConfidence(), 0f, domainResult.getNormalizedEntropy(),
                domainResult.getExplanation());

        return new ResearchHarnessOutcome(
                ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE, false, domainResult.getDomain(),
                domainResult.getConfidence(), null, 0f, dec,
                "Domain gate rejected: " + domainResult.getExplanation());
    }

    @NonNull
    private ResearchHarnessOutcome evaluateDiseaseModelAndPolicy(
            @NonNull ByteBuffer floatBuffer,
            @NonNull CropDomainResult domainResult) {
        float[] logits = null;
        boolean available = (diseaseExecutor != null && diseaseExecutor.isAvailable());
        if (available) {
            try {
                floatBuffer.rewind();
                logits = diseaseExecutor.execute(floatBuffer);
            } catch (Throwable t) {
                available = false;
            }
        }

        LeafGateValidationStatus leafStatus = LeafGateValidationStatus.validatedAndAccepted(
                "Foliar coverage validated");

        InferenceDecisionRequest request = new InferenceDecisionRequest.Builder()
                .setRawOutput(logits)
                .setIsLogits(true)
                .setModelAvailable(available)
                .setLeafGateStatus(leafStatus)
                .setCropDomainResult(domainResult)
                .setCropContext(domainResult.isRice() ? "rice" : "groundnut")
                .build();

        InferenceDecisionResult dec = policy.evaluate(request);
        return new ResearchHarnessOutcome(
                ResearchHarnessOutcome.Stage.DECISION_POLICY,
                dec.isAccepted(),
                domainResult.getDomain(),
                domainResult.getConfidence(),
                dec.getClassId(),
                dec.getConfidence(),
                dec,
                dec.getExplanation());
    }

    @NonNull
    public static ByteBuffer prepareNormalizedFloatBuffer(@NonNull int[] pixels) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1 * 3 * 224 * 224 * 4).order(ByteOrder.nativeOrder());
        buf.rewind();
        int numPixels = pixels.length;
        // Channel 0: Red plane
        for (int i = 0; i < numPixels; i++) {
            float r = (((pixels[i] >> 16) & 0xFF) / 255.0f - IMAGENET_MEAN_R) / IMAGENET_STD_R;
            buf.putFloat(r);
        }
        // Channel 1: Green plane
        for (int i = 0; i < numPixels; i++) {
            float g = (((pixels[i] >> 8) & 0xFF) / 255.0f - IMAGENET_MEAN_G) / IMAGENET_STD_G;
            buf.putFloat(g);
        }
        // Channel 2: Blue plane
        for (int i = 0; i < numPixels; i++) {
            float b = ((pixels[i] & 0xFF) / 255.0f - IMAGENET_MEAN_B) / IMAGENET_STD_B;
            buf.putFloat(b);
        }
        buf.rewind();
        return buf;
    }
}
