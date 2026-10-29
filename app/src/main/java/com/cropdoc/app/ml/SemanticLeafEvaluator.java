package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import java.nio.ByteBuffer;

/**
 * Explicit inference contract for a genuine semantic leaf-vs-nonleaf evaluator (Phase F).
 *
 * Implementations must be backed by an independently trained and evaluated model
 * adhering to the semantic leaf gate specification (e.g. binary classification with
 * false acceptance rate < 0.1% on negative non-leaf datasets).
 *
 * In accordance with FAIL CLOSED architecture, ValidatedLeafGate requires a genuine,
 * non-null SemanticLeafEvaluator before declaring the gate state VALIDATED.
 */
public interface SemanticLeafEvaluator {

    /**
     * Unique identifier and model version of the semantic leaf evaluator.
     */
    @NonNull
    String getEvaluatorId();

    /**
     * Evaluates whether the normalized preprocessed tensor represents a valid crop leaf.
     *
     * @param preprocessedBitmap The 224x224 RGB preprocessed bitmap.
     * @param tensorBuffer The preprocessed tensor buffer (224x224x3).
     * @return LeafGate.GateResult with ACCEPT, REJECT(NON_LEAF_DETECTED), or UNCERTAIN.
     */
    @NonNull
    LeafGate.GateResult evaluateSemanticLeaf(
            @NonNull Bitmap preprocessedBitmap,
            @NonNull ByteBuffer tensorBuffer);
}
