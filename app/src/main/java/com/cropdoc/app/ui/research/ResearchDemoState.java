package com.cropdoc.app.ui.research;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.research.CottonBinaryResearchClassifier;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import java.util.Objects;

/** Immutable state for Debug-only research inference. */
public final class ResearchDemoState {

    public enum Status {
        NO_IMAGE_SELECTED,
        IMAGE_READY,
        ANALYZING,
        ACCEPTED,
        INCONCLUSIVE,
        OUT_OF_DOMAIN,
        RESEARCH_ONLY_RESULT,
        ERROR
    }

    @NonNull private final Status status;
    @Nullable private final Bitmap previewBitmap;
    @Nullable private final ResearchHarnessOutcome outcome;
    @Nullable private final CottonBinaryResearchClassifier.Prediction cottonPrediction;
    private final long totalLatencyMs;
    private final long domainLatencyMs;
    private final long diseaseLatencyMs;
    @Nullable private final String message;
    private final boolean modelsAvailable;

    public ResearchDemoState(
            @NonNull Status status,
            @Nullable Bitmap previewBitmap,
            @Nullable ResearchHarnessOutcome outcome,
            long totalLatencyMs,
            long domainLatencyMs,
            long diseaseLatencyMs,
            @Nullable String message,
            boolean modelsAvailable) {
        this(status, previewBitmap, outcome, null, totalLatencyMs, domainLatencyMs,
                diseaseLatencyMs, message, modelsAvailable);
    }

    private ResearchDemoState(
            @NonNull Status status,
            @Nullable Bitmap previewBitmap,
            @Nullable ResearchHarnessOutcome outcome,
            @Nullable CottonBinaryResearchClassifier.Prediction cottonPrediction,
            long totalLatencyMs,
            long domainLatencyMs,
            long diseaseLatencyMs,
            @Nullable String message,
            boolean modelsAvailable) {
        this.status = Objects.requireNonNull(status, "status");
        this.previewBitmap = previewBitmap;
        this.outcome = outcome;
        this.cottonPrediction = cottonPrediction;
        this.totalLatencyMs = totalLatencyMs;
        this.domainLatencyMs = domainLatencyMs;
        this.diseaseLatencyMs = diseaseLatencyMs;
        this.message = message;
        this.modelsAvailable = modelsAvailable;
    }

    @NonNull
    public static ResearchDemoState noImageSelected(boolean modelsAvailable, @Nullable String note) {
        return new ResearchDemoState(
                Status.NO_IMAGE_SELECTED, null, null, null, 0L, -1L, -1L, note, modelsAvailable);
    }

    @NonNull
    public static ResearchDemoState imageReady(@NonNull Bitmap preview, boolean modelsAvailable) {
        return new ResearchDemoState(
                Status.IMAGE_READY, preview, null, null, 0L, -1L, -1L, null, modelsAvailable);
    }

    @NonNull
    public static ResearchDemoState analyzing(@Nullable Bitmap preview, boolean modelsAvailable) {
        return new ResearchDemoState(
                Status.ANALYZING, preview, null, null, 0L, -1L, -1L, null, modelsAvailable);
    }

    @NonNull
    public static ResearchDemoState completed(
            @NonNull Bitmap preview,
            @NonNull ResearchHarnessOutcome outcome,
            long totalLatencyMs,
            long domainLatencyMs,
            long diseaseLatencyMs,
            boolean modelsAvailable) {
        Status s;
        if (outcome.isAccepted()) {
            s = Status.ACCEPTED;
        } else if (outcome.getDecisionResult().getOutcomeType() == PolicyOutcomeType.OUT_OF_DOMAIN) {
            s = Status.OUT_OF_DOMAIN;
        } else {
            s = Status.INCONCLUSIVE;
        }
        return new ResearchDemoState(
                s, preview, outcome, null, totalLatencyMs, domainLatencyMs, diseaseLatencyMs,
                outcome.getExplanation(), modelsAvailable);
    }

    /** A separate cotton pilot result; it is deliberately not a production/policy decision. */
    @NonNull
    public static ResearchDemoState cottonCompleted(
            @NonNull Bitmap preview,
            @NonNull CottonBinaryResearchClassifier.Prediction prediction,
            long totalLatencyMs) {
        return new ResearchDemoState(
                Status.RESEARCH_ONLY_RESULT, preview, null, prediction, totalLatencyMs,
                -1L, prediction.getInferenceTimeMs(), prediction.getDisclaimer(), true);
    }

    @NonNull
    public static ResearchDemoState error(
            @Nullable Bitmap preview,
            @NonNull String message,
            boolean modelsAvailable) {
        return new ResearchDemoState(
                Status.ERROR, preview, null, null, 0L, -1L, -1L, message, modelsAvailable);
    }

    @NonNull
    public static ResearchDemoState idle(boolean modelsAvailable, @Nullable String note) {
        return noImageSelected(modelsAvailable, note);
    }

    @NonNull
    public static ResearchDemoState loading(@Nullable Bitmap preview, boolean modelsAvailable) {
        return analyzing(preview, modelsAvailable);
    }

    @NonNull public Status getStatus() { return status; }
    @Nullable public Bitmap getPreviewBitmap() { return previewBitmap; }
    @Nullable public ResearchHarnessOutcome getOutcome() { return outcome; }
    @Nullable public CottonBinaryResearchClassifier.Prediction getCottonPrediction() { return cottonPrediction; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public long getDomainLatencyMs() { return domainLatencyMs; }
    public long getDiseaseLatencyMs() { return diseaseLatencyMs; }
    @Nullable public String getMessage() { return message; }
    public boolean isModelsAvailable() { return modelsAvailable; }
}
