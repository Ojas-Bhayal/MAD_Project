package com.cropdoc.app.ml.policy;

/**
 * Mutually exclusive outcome categories produced by the inference decision policy.
 * Non-diagnostic outcomes are strongly separated to prevent any unvalidated, out-of-domain,
 * or ambiguous result from ever producing disease diagnosis or agronomic advice.
 */
public enum PolicyOutcomeType {

    /**
     * Confident, validated prediction for one of the verified supported classes
     * with passing leaf gate, valid crop domain, and unambiguous margin.
     */
    ACCEPTED_SUPPORTED_CLASS,

    /**
     * Prediction lacks sufficient confidence or has ambiguous top-2 margin / high entropy.
     * Diagnostic advice must not be rendered; user should be prompted to retake scan.
     */
    INCONCLUSIVE_LOW_CONFIDENCE,

    /**
     * Input does not belong to the supported domain (e.g. non-leaf, background,
     * unsupported crop species, or high OOD distance).
     */
    OUT_OF_DOMAIN,

    /**
     * Model output is corrupted, non-finite, degenerate, or has invalid dimensions.
     */
    INVALID_MODEL_OUTPUT,

    /**
     * Required inference or gate components are unavailable, uninitialized, or unvalidated.
     */
    MODEL_UNAVAILABLE
}
