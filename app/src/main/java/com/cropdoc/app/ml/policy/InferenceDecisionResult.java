package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable strongly-typed result produced by {@link InferenceDecisionPolicy}.
 *
 * Implements strict boundary guards ensuring non-diagnostic outcomes cannot produce
 * disease diagnosis or agronomic advice.
 */
public final class InferenceDecisionResult {

    @NonNull private final PolicyOutcomeType outcomeType;
    @NonNull private final PolicyRejectionDetail rejectionDetail;
    private final int localIndex;
    private final int globalIndex;
    @Nullable private final String classId;
    @Nullable private final String crop;
    private final boolean isHealthy;
    private final float confidence;
    private final float margin;
    private final float normalizedEntropy;
    @NonNull private final String explanation;

    public InferenceDecisionResult(
            @NonNull PolicyOutcomeType outcomeType,
            @NonNull PolicyRejectionDetail rejectionDetail,
            int localIndex,
            int globalIndex,
            @Nullable String classId,
            @Nullable String crop,
            boolean isHealthy,
            float confidence,
            float margin,
            float normalizedEntropy,
            @NonNull String explanation) {
        this.outcomeType = Objects.requireNonNull(outcomeType, "outcomeType");
        this.rejectionDetail = Objects.requireNonNull(rejectionDetail, "rejectionDetail");
        this.localIndex = localIndex;
        this.globalIndex = globalIndex;
        this.classId = classId;
        this.crop = crop;
        this.isHealthy = isHealthy;
        this.confidence = confidence;
        this.margin = margin;
        this.normalizedEntropy = normalizedEntropy;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @NonNull
    public PolicyOutcomeType getOutcomeType() {
        return outcomeType;
    }

    @NonNull
    public PolicyRejectionDetail getRejectionDetail() {
        return rejectionDetail;
    }

    public int getLocalIndex() {
        return localIndex;
    }

    public int getGlobalIndex() {
        return globalIndex;
    }

    @Nullable
    public String getClassId() {
        return classId;
    }

    @Nullable
    public String getCrop() {
        return crop;
    }

    public boolean isHealthy() {
        return isHealthy;
    }

    public float getConfidence() {
        return confidence;
    }

    public float getMargin() {
        return margin;
    }

    public float getNormalizedEntropy() {
        return normalizedEntropy;
    }

    @NonNull
    public String getExplanation() {
        return explanation;
    }

    /**
     * True ONLY if the prediction passed all quality, gate, domain, and confidence checks.
     */
    public boolean isAccepted() {
        return outcomeType == PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS;
    }

    /**
     * True ONLY if the prediction is an accepted, valid diagnostic result.
     */
    public boolean isDiagnostic() {
        return isAccepted();
    }

    /**
     * True ONLY if the prediction is an accepted disease diagnosis (not asymptomatic/healthy).
     */
    public boolean isDiseaseDiagnosis() {
        return isAccepted() && !isHealthy;
    }

    /**
     * Boundary Guard: Strictly requires confirmed disease diagnosis before returning classId.
     * Throws IllegalStateException for any non-diagnostic outcome or healthy specimen.
     */
    @NonNull
    public String requireDiseaseClassId() {
        if (!isDiseaseDiagnosis() || classId == null) {
            throw new IllegalStateException(
                    "Agronomic Boundary Guard: Cannot retrieve disease diagnosis for non-disease outcome: "
                            + outcomeType + " (" + rejectionDetail + ")");
        }
        return classId;
    }

    /**
     * Boundary Guard: Strictly requires confirmed disease diagnosis before retrieving agronomic advice.
     */
    @NonNull
    public String requireAgronomicAdviceClassId() {
        return requireDiseaseClassId();
    }

    @NonNull
    @Override
    public String toString() {
        return "InferenceDecisionResult{"
                + "outcome=" + outcomeType
                + ", detail=" + rejectionDetail
                + ", classId='" + classId + '\''
                + ", conf=" + confidence
                + ", margin=" + margin
                + ", entropy=" + normalizedEntropy
                + '}';
    }
}
