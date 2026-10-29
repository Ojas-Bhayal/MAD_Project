package com.cropdoc.app.domain.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable domain representation of a saved scan entry.
 */
public final class ScanRecord {

    @NonNull
    private final String scanId;

    @NonNull
    private final String userId;

    @NonNull
    private final String cropId;

    @NonNull
    private final String diseaseId;

    private final float confidence;

    @NonNull
    private final DiagnosisOutcome outcome;

    @NonNull
    private final String localImagePath;

    @Nullable
    private final String remoteImageUrl;

    private final long timestampMs;
    private final boolean synced;

    @Nullable
    private final Boolean userFeedbackHelpful;

    @NonNull
    private final String modelVersion;

    public ScanRecord(
            @NonNull String scanId,
            @NonNull String userId,
            @NonNull String cropId,
            @NonNull String diseaseId,
            float confidence,
            @NonNull DiagnosisOutcome outcome,
            @NonNull String localImagePath,
            @Nullable String remoteImageUrl,
            long timestampMs,
            boolean synced,
            @Nullable Boolean userFeedbackHelpful,
            @NonNull String modelVersion) {
        this.scanId = Objects.requireNonNull(scanId, "scanId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.cropId = Objects.requireNonNull(cropId, "cropId");
        this.diseaseId = Objects.requireNonNull(diseaseId, "diseaseId");
        this.confidence = confidence;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.localImagePath = Objects.requireNonNull(localImagePath, "localImagePath");
        this.remoteImageUrl = remoteImageUrl;
        this.timestampMs = timestampMs;
        this.synced = synced;
        this.userFeedbackHelpful = userFeedbackHelpful;
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
    }

    @NonNull
    public String getScanId() {
        return scanId;
    }

    @NonNull
    public String getUserId() {
        return userId;
    }

    @NonNull
    public String getCropId() {
        return cropId;
    }

    @NonNull
    public String getDiseaseId() {
        return diseaseId;
    }

    public float getConfidence() {
        return confidence;
    }

    @NonNull
    public DiagnosisOutcome getOutcome() {
        return outcome;
    }

    @NonNull
    public String getLocalImagePath() {
        return localImagePath;
    }

    @Nullable
    public String getRemoteImageUrl() {
        return remoteImageUrl;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public boolean isSynced() {
        return synced;
    }

    @Nullable
    public Boolean getUserFeedbackHelpful() {
        return userFeedbackHelpful;
    }

    @NonNull
    public String getModelVersion() {
        return modelVersion;
    }
}
