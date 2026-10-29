package com.cropdoc.app.ml.policy;

/**
 * Detailed diagnostic codes explaining why an inference result was rejected or marked non-diagnostic.
 */
public enum PolicyRejectionDetail {
    NONE,

    // Model availability / integrity
    MODEL_NOT_AVAILABLE,
    LEAF_GATE_NOT_PRESENT,
    LEAF_GATE_UNVALIDATED,

    // Structural model output validity
    OUTPUT_NULL_OR_EMPTY,
    INVALID_CLASS_COUNT,
    NON_FINITE_VALUES,
    INVALID_PROBABILITY_DISTRIBUTION,
    DEGENERATE_UNIFORM_OUTPUT,

    // Domain and semantic gate rejection
    LEAF_GATE_REJECTED_NON_LEAF,
    UNSUPPORTED_CROP_DOMAIN,
    OOD_SCORE_EXCEEDED,
    CROP_DOMAIN_GATE_NOT_PRESENT,
    CROP_DOMAIN_GATE_UNVALIDATED,
    CROP_DOMAIN_UNCERTAIN,
    CROP_DOMAIN_MISMATCH,

    // Confidence / Ambiguity rejection
    CONFIDENCE_BELOW_THRESHOLD,
    MARGIN_BELOW_THRESHOLD,
    ENTROPY_ABOVE_THRESHOLD
}
