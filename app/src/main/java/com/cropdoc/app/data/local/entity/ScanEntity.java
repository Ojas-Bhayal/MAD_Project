package com.cropdoc.app.data.local.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/**
 * Room entity caching leaf diagnosis records for instant offline access and deferred cloud sync.
 */
@Entity(
        tableName = "scans",
        indices = {
            @Index(value = {"user_id", "timestamp_ms"}),
            @Index(value = {"synced"})
        })
public final class ScanEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "scan_id")
    private final String scanId;

    @NonNull
    @ColumnInfo(name = "user_id")
    private final String userId;

    @NonNull
    @ColumnInfo(name = "crop_id")
    private final String cropId;

    @NonNull
    @ColumnInfo(name = "disease_id")
    private final String diseaseId;

    @ColumnInfo(name = "confidence")
    private final float confidence;

    @NonNull
    @ColumnInfo(name = "outcome")
    private final String outcome;

    @NonNull
    @ColumnInfo(name = "local_image_path")
    private final String localImagePath;

    @Nullable
    @ColumnInfo(name = "remote_image_url")
    private final String remoteImageUrl;

    @ColumnInfo(name = "timestamp_ms")
    private final long timestampMs;

    @ColumnInfo(name = "synced")
    private final boolean synced;

    @Nullable
    @ColumnInfo(name = "user_feedback_helpful")
    private final Boolean userFeedbackHelpful;

    @NonNull
    @ColumnInfo(name = "model_version")
    private final String modelVersion;

    public ScanEntity(
            @NonNull String scanId,
            @NonNull String userId,
            @NonNull String cropId,
            @NonNull String diseaseId,
            float confidence,
            @NonNull String outcome,
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
    public String getOutcome() {
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
