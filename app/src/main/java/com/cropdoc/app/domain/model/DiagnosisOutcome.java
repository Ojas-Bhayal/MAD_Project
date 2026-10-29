package com.cropdoc.app.domain.model;

/**
 * Explicit classification states used to drive safe agronomic UI behaviour.
 */
public enum DiagnosisOutcome {
    CONFIDENT_DISEASE,
    HEALTHY_LEAF,
    LOW_CONFIDENCE_RETAKE,
    NOT_A_SUPPORTED_LEAF
}
