package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;

/**
 * Calibrated threshold configuration for the inference decision policy.
 * Thresholds calibrated strictly on independent validation data (216 canonical specimens).
 */
public final class InferencePolicyConfig {

    public static final int DEFAULT_EXPECTED_CLASSES = SixClassResearchMapping.NUM_RESEARCH_CLASSES;
    public static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.60f;
    public static final float DEFAULT_MARGIN_THRESHOLD = 0.35f;
    public static final float DEFAULT_MAX_NORMALIZED_ENTROPY = 0.55f;
    public static final float DEFAULT_TEMPERATURE = 1.0f;

    private final int expectedClasses;
    private final float confidenceThreshold;
    private final float marginThreshold;
    private final float maxNormalizedEntropy;
    private final float temperature;
    private final boolean enforceCropDomain;
    private final boolean enforceLeafGate;
    private final boolean enforceCropDomainGate;

    public InferencePolicyConfig(
            int expectedClasses,
            float confidenceThreshold,
            float marginThreshold,
            float maxNormalizedEntropy,
            float temperature,
            boolean enforceCropDomain,
            boolean enforceLeafGate,
            boolean enforceCropDomainGate) {
        this.expectedClasses = expectedClasses;
        this.confidenceThreshold = confidenceThreshold;
        this.marginThreshold = marginThreshold;
        this.maxNormalizedEntropy = maxNormalizedEntropy;
        this.temperature = temperature;
        this.enforceCropDomain = enforceCropDomain;
        this.enforceLeafGate = enforceLeafGate;
        this.enforceCropDomainGate = enforceCropDomainGate;
    }

    public InferencePolicyConfig(
            int expectedClasses,
            float confidenceThreshold,
            float marginThreshold,
            float maxNormalizedEntropy,
            float temperature,
            boolean enforceCropDomain,
            boolean enforceLeafGate) {
        this(expectedClasses, confidenceThreshold, marginThreshold,
                maxNormalizedEntropy, temperature, enforceCropDomain,
                enforceLeafGate, false);
    }

    @NonNull
    public static InferencePolicyConfig createCalibrated() {
        return new InferencePolicyConfig(
                DEFAULT_EXPECTED_CLASSES,
                DEFAULT_CONFIDENCE_THRESHOLD,
                DEFAULT_MARGIN_THRESHOLD,
                DEFAULT_MAX_NORMALIZED_ENTROPY,
                DEFAULT_TEMPERATURE,
                true,
                true,
                false);
    }

    @NonNull
    public static InferencePolicyConfig createWithCropDomainGate() {
        return new InferencePolicyConfig(
                DEFAULT_EXPECTED_CLASSES,
                DEFAULT_CONFIDENCE_THRESHOLD,
                DEFAULT_MARGIN_THRESHOLD,
                DEFAULT_MAX_NORMALIZED_ENTROPY,
                DEFAULT_TEMPERATURE,
                true,
                true,
                true);
    }

    public int getExpectedClasses() { return expectedClasses; }
    public float getConfidenceThreshold() { return confidenceThreshold; }
    public float getMarginThreshold() { return marginThreshold; }
    public float getMaxNormalizedEntropy() { return maxNormalizedEntropy; }
    public float getTemperature() { return temperature; }
    public boolean isEnforceCropDomain() { return enforceCropDomain; }
    public boolean isEnforceLeafGate() { return enforceLeafGate; }
    public boolean isEnforceCropDomainGate() { return enforceCropDomainGate; }
}
