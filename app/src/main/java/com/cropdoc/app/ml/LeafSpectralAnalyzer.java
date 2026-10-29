package com.cropdoc.app.ml;

import androidx.annotation.NonNull;

/**
 * Non-diagnostic utility class retained strictly for numerical degenerate-output detection.
 *
 * All heuristic foliar logit synthesis (populateFoliarLogits, resolveClassLogit,
 * computeNormalizedLesionsFromCore, extractCoreAndStats) has been completely removed to prevent
 * unverified heuristic disease diagnoses from reaching farmers or the database (AUD-ML-001).
 */
public final class LeafSpectralAnalyzer {

    private static final float EPSILON = 1e-4f;

    private LeafSpectralAnalyzer() {
        // Utility class
    }

    /**
     * Checks whether an array of model logits is all zeros or numerically uniform.
     * Used by ModelValidator and LiteRtLeafClassifier to detect degenerate model outputs.
     */
    public static boolean isAllZeroOrUniform(@NonNull float[] logits) {
        if (logits.length == 0) {
            return true;
        }
        float first = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (Math.abs(logits[i] - first) > EPSILON) {
                return false;
            }
        }
        return true;
    }
}
