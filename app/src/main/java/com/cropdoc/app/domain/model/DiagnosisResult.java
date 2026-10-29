package com.cropdoc.app.domain.model;

import androidx.annotation.NonNull;
import java.util.Objects;

/**
 * Immutable output from the on-device LiteRT leaf classifier.
 */
public final class DiagnosisResult {

    @NonNull
    private final String labelId;

    @NonNull
    private final String cropId;

    private final float confidence;
    private final float normalizedEntropy;
    private final float leafPixelRatio;
    private final long inferenceTimeMs;

    @NonNull
    private final String modelVersion;

    @NonNull
    private final DiagnosisOutcome outcome;

    public DiagnosisResult(
            @NonNull String labelId,
            @NonNull String cropId,
            float confidence,
            float normalizedEntropy,
            float leafPixelRatio,
            long inferenceTimeMs,
            @NonNull String modelVersion,
            @NonNull DiagnosisOutcome outcome) {
        this.labelId = Objects.requireNonNull(labelId, "labelId");
        this.cropId = Objects.requireNonNull(cropId, "cropId");
        this.confidence = confidence;
        this.normalizedEntropy = normalizedEntropy;
        this.leafPixelRatio = leafPixelRatio;
        this.inferenceTimeMs = inferenceTimeMs;
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    @NonNull
    public String getLabelId() {
        return labelId;
    }

    @NonNull
    public String getCropId() {
        return cropId;
    }

    public float getConfidence() {
        return confidence;
    }

    public float getNormalizedEntropy() {
        return normalizedEntropy;
    }

    public float getLeafPixelRatio() {
        return leafPixelRatio;
    }

    public long getInferenceTimeMs() {
        return inferenceTimeMs;
    }

    @NonNull
    public String getModelVersion() {
        return modelVersion;
    }

    @NonNull
    public DiagnosisOutcome getOutcome() {
        return outcome;
    }
}
