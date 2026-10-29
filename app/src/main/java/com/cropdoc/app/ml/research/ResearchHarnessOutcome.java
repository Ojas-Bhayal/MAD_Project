package com.cropdoc.app.ml.research;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ml.policy.CropDomain;
import com.cropdoc.app.ml.policy.InferenceDecisionResult;
import java.util.Objects;

/**
 * Result record from the isolated two-model research inference harness.
 */
public final class ResearchHarnessOutcome {

    public enum Stage {
        QUALITY_CHECK,
        FOLIAR_GATE,
        CROP_DOMAIN_GATE,
        DISEASE_CLASSIFIER,
        DECISION_POLICY
    }

    @NonNull private final Stage terminalStage;
    private final boolean isAccepted;
    @Nullable private final CropDomain detectedDomain;
    private final float domainConfidence;
    @Nullable private final String predictedClassId;
    private final float diseaseConfidence;
    @NonNull private final InferenceDecisionResult decisionResult;
    @NonNull private final String explanation;

    public ResearchHarnessOutcome(
            @NonNull Stage terminalStage,
            boolean isAccepted,
            @Nullable CropDomain detectedDomain,
            float domainConfidence,
            @Nullable String predictedClassId,
            float diseaseConfidence,
            @NonNull InferenceDecisionResult decisionResult,
            @NonNull String explanation) {
        this.terminalStage = Objects.requireNonNull(terminalStage, "terminalStage");
        this.isAccepted = isAccepted;
        this.detectedDomain = detectedDomain;
        this.domainConfidence = domainConfidence;
        this.predictedClassId = predictedClassId;
        this.diseaseConfidence = diseaseConfidence;
        this.decisionResult = Objects.requireNonNull(decisionResult, "decisionResult");
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @NonNull public Stage getTerminalStage() { return terminalStage; }
    public boolean isAccepted() { return isAccepted; }
    @Nullable public CropDomain getDetectedDomain() { return detectedDomain; }
    public float getDomainConfidence() { return domainConfidence; }
    @Nullable public String getPredictedClassId() { return predictedClassId; }
    public float getDiseaseConfidence() { return diseaseConfidence; }
    @NonNull public InferenceDecisionResult getDecisionResult() { return decisionResult; }
    @NonNull public String getExplanation() { return explanation; }
}
