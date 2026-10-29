package com.cropdoc.app.ui.scan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * One-shot state for experimental camera/gallery inference in the Debug Scan screen.
 * This state is deliberately separate from production AnalysisOutput and is never persisted.
 */
public final class ScanResearchState {

    public enum Status {
        IDLE,
        LOADING,
        RESULT,
        REJECTED,
        UNAVAILABLE,
        ERROR
    }

    @NonNull private final Status status;
    @NonNull private final String cropKey;
    @Nullable private final String classId;
    private final float modelScore;
    private final long inferenceTimeMs;
    @Nullable private final String message;

    private ScanResearchState(
            @NonNull Status status,
            @NonNull String cropKey,
            @Nullable String classId,
            float modelScore,
            long inferenceTimeMs,
            @Nullable String message) {
        this.status = Objects.requireNonNull(status, "status");
        this.cropKey = Objects.requireNonNull(cropKey, "cropKey");
        this.classId = classId;
        this.modelScore = modelScore;
        this.inferenceTimeMs = inferenceTimeMs;
        this.message = message;
    }

    @NonNull
    public static ScanResearchState idle() {
        return new ScanResearchState(Status.IDLE, "rice", null, 0f, 0L, null);
    }

    @NonNull
    public static ScanResearchState loading(@NonNull String cropKey) {
        return new ScanResearchState(Status.LOADING, cropKey, null, 0f, 0L, null);
    }

    @NonNull
    public static ScanResearchState result(
            @NonNull String cropKey, @NonNull String classId, float modelScore,
            long inferenceTimeMs, @Nullable String message) {
        return new ScanResearchState(
                Status.RESULT, cropKey, classId, modelScore, inferenceTimeMs, message);
    }

    @NonNull
    public static ScanResearchState rejected(@NonNull String cropKey, @NonNull String message) {
        return new ScanResearchState(Status.REJECTED, cropKey, null, 0f, 0L, message);
    }

    @NonNull
    public static ScanResearchState unavailable(@NonNull String cropKey, @NonNull String message) {
        return new ScanResearchState(Status.UNAVAILABLE, cropKey, null, 0f, 0L, message);
    }

    @NonNull
    public static ScanResearchState error(@NonNull String cropKey, @NonNull String message) {
        return new ScanResearchState(Status.ERROR, cropKey, null, 0f, 0L, message);
    }

    @NonNull public Status getStatus() { return status; }
    @NonNull public String getCropKey() { return cropKey; }
    @Nullable public String getClassId() { return classId; }
    public float getModelScore() { return modelScore; }
    public long getInferenceTimeMs() { return inferenceTimeMs; }
    @Nullable public String getMessage() { return message; }
    public boolean isLoading() { return status == Status.LOADING; }
    public boolean isTerminal() { return status != Status.IDLE && status != Status.LOADING; }
}
