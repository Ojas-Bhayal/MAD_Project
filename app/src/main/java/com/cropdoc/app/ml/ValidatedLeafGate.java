package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Validated implementation of LeafGate (Phase G - Evidence-Based Validation).
 *
 * In accordance with FAIL CLOSED architecture:
 * 1. A non-null interface implementation alone does NOT establish that a gate is validated.
 * 2. Requires a verified GateValidationEvidence bundle including:
 *    - Model SHA-256 and labels checksum.
 *    - Input/output tensor dimensions and dtypes.
 *    - Preprocessing contract ID and version.
 *    - Independently generated evaluation report whose hash matches manifest.
 *    - Measured acceptance/rejection metrics meeting strict thresholds.
 * 3. If any evidence is absent, invalid, stale, or mismatched, state remains PRESENT_UNVALIDATED
 *    and evaluation returns UNCERTAIN. It NEVER returns ACCEPT without certified evidence.
 */
public final class ValidatedLeafGate implements LeafGate {

    @NonNull private final String version;
    @NonNull private final ImageQualityGate qualityGate;
    @Nullable private final SemanticLeafEvaluator semanticEvaluator;
    @Nullable private final GateValidationEvidence evidence;

    public ValidatedLeafGate(
            @NonNull String version,
            @NonNull ImageQualityGate qualityGate,
            @NonNull SemanticLeafEvaluator semanticEvaluator,
            @Nullable GateValidationEvidence evidence) {
        this.version = Objects.requireNonNull(version, "version");
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.semanticEvaluator = Objects.requireNonNull(semanticEvaluator, "semanticEvaluator");
        this.evidence = evidence;
    }

    public ValidatedLeafGate(
            @NonNull String version,
            @NonNull SemanticLeafEvaluator semanticEvaluator,
            @Nullable GateValidationEvidence evidence) {
        this(version, new ImageQualityGate(), semanticEvaluator, evidence);
    }

    /**
     * Backward-compatible constructor without explicit evidence.
     * Fails closed: lacking certified evidence, state remains PRESENT_UNVALIDATED.
     */
    public ValidatedLeafGate(
            @NonNull String version,
            @NonNull SemanticLeafEvaluator semanticEvaluator) {
        this(version, new ImageQualityGate(), semanticEvaluator, null);
    }

    /**
     * Test factory for unvalidated / absent evaluator scenarios.
     */
    static ValidatedLeafGate createWithoutEvaluator(
            @NonNull String version,
            @NonNull ImageQualityGate qualityGate) {
        return new ValidatedLeafGate(version, qualityGate, null, null, false);
    }

    private ValidatedLeafGate(
            @NonNull String version,
            @NonNull ImageQualityGate qualityGate,
            @Nullable SemanticLeafEvaluator semanticEvaluator,
            @Nullable GateValidationEvidence evidence,
            boolean unused) {
        this.version = Objects.requireNonNull(version, "version");
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.semanticEvaluator = semanticEvaluator;
        this.evidence = evidence;
    }

    @NonNull
    @Override
    public GateState getState() {
        if (semanticEvaluator == null || evidence == null || !evidence.isCertified()) {
            return GateState.PRESENT_UNVALIDATED;
        }
        return GateState.VALIDATED;
    }

    @NonNull
    @Override
    public String getVersion() {
        return version;
    }

    @Nullable
    public GateValidationEvidence getValidationEvidence() {
        return evidence;
    }

    @NonNull
    @Override
    public GateResult evaluate(@NonNull Bitmap preprocessedBitmap, @NonNull ByteBuffer tensorBuffer) {
        // 1. Image Quality Pre-check (exposure, dimensions)
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(preprocessedBitmap, tensorBuffer);
        if (!quality.isAccepted()) {
            return GateResult.reject(
                    quality.getRejectionReason() != null
                            ? quality.getRejectionReason()
                            : ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED,
                    quality.getExplanation());
        }

        // 2. Semantic Leaf Validation - FAIL CLOSED if evaluator or certified evidence is missing
        if (semanticEvaluator == null) {
            return GateResult.uncertain(
                    "Semantic evaluator is missing for gate (" + version + "). Cannot confirm leaf presence.");
        }
        if (evidence == null || !evidence.isCertified()) {
            return GateResult.uncertain(
                    "Semantic gate (" + version + ") lacks certified evidence. Cannot validate leaf presence.");
        }

        // 3. Execute Genuine Semantic Evaluation
        try {
            GateResult result = semanticEvaluator.evaluateSemanticLeaf(preprocessedBitmap, tensorBuffer);
            if (result == null) {
                return GateResult.uncertain(
                        "Semantic evaluator returned null result for gate (" + version + ").");
            }
            return result;
        } catch (Throwable t) {
            return GateResult.uncertain(
                    "Semantic leaf evaluation failed: " + t.getMessage());
        }
    }
}
