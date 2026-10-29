package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Stage 2: Semantic LeafGate Interface (G2).
 *
 * Explicitly models gate validation state:
 * - NOT_PRESENT: No semantic gate model is installed.
 * - PRESENT_UNVALIDATED: A gate model or stub is present but lacks an independently verified
 *   evaluation report or manifest.
 * - VALIDATED: Gate model passes all manifest integrity checks, has a valid evaluation report
 *   matching hash on disk, and passes empirical acceptance thresholds.
 *
 * In accordance with FAIL CLOSED architecture, any state other than VALIDATED disables diagnosis
 * and returns GateUnavailable / GateUnvalidated.
 */
public interface LeafGate {

    enum GateState {
        NOT_PRESENT,
        PRESENT_UNVALIDATED,
        VALIDATED
    }

    enum GateDecision {
        ACCEPT,
        REJECT,
        UNCERTAIN
    }

    final class GateResult {
        @NonNull
        private final GateDecision decision;
        @Nullable
        private final ClassificationOutcome.RejectionReason rejectionReason;
        @NonNull
        private final String explanation;

        private GateResult(
                @NonNull GateDecision decision,
                @Nullable ClassificationOutcome.RejectionReason rejectionReason,
                @NonNull String explanation) {
            this.decision = Objects.requireNonNull(decision, "decision");
            this.rejectionReason = rejectionReason;
            this.explanation = Objects.requireNonNull(explanation, "explanation");
        }

        @NonNull
        public static GateResult accept(@NonNull String explanation) {
            return new GateResult(GateDecision.ACCEPT, null, explanation);
        }

        @NonNull
        public static GateResult reject(
                @NonNull ClassificationOutcome.RejectionReason reason,
                @NonNull String explanation) {
            return new GateResult(GateDecision.REJECT, reason, explanation);
        }

        @NonNull
        public static GateResult uncertain(@NonNull String explanation) {
            return new GateResult(GateDecision.UNCERTAIN, null, explanation);
        }

        @NonNull
        public GateDecision getDecision() {
            return decision;
        }

        @Nullable
        public ClassificationOutcome.RejectionReason getRejectionReason() {
            return rejectionReason;
        }

        @NonNull
        public String getExplanation() {
            return explanation;
        }

        @NonNull
        @Override
        public String toString() {
            return "GateResult{" + decision + ", reason=" + rejectionReason
                    + ", explanation='" + explanation + "'}";
        }
    }

    @NonNull
    GateState getState();

    @Nullable
    String getVersion();

    @NonNull
    GateResult evaluate(@NonNull Bitmap preprocessedBitmap, @NonNull ByteBuffer tensorBuffer);
}
