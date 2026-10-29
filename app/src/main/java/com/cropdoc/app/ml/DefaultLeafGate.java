package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Default LeafGate implementation.
 *
 * Wraps Stage 1 ImageQualityGate for cheap exposure/dimension checks, but explicitly declares
 * state as PRESENT_UNVALIDATED because no independently trained and benchmarked leaf-vs-nonleaf
 * model is currently installed.
 *
 * In accordance with FAIL CLOSED architecture (G2), this causes the classifier to reject
 * diagnosis with GATE_UNVALIDATED until a validated model is attached.
 */
public final class DefaultLeafGate implements LeafGate {

    @NonNull
    private final ImageQualityGate qualityGate;

    public DefaultLeafGate() {
        this(new ImageQualityGate());
    }

    public DefaultLeafGate(@NonNull ImageQualityGate qualityGate) {
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
    }

    @NonNull
    @Override
    public GateState getState() {
        return GateState.PRESENT_UNVALIDATED;
    }

    @Nullable
    @Override
    public String getVersion() {
        return null;
    }

    @NonNull
    @Override
    public GateResult evaluate(@NonNull Bitmap preprocessedBitmap, @NonNull ByteBuffer tensorBuffer) {
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(preprocessedBitmap, tensorBuffer);
        if (!quality.isAccepted()) {
            return GateResult.reject(
                    quality.getRejectionReason() != null
                            ? quality.getRejectionReason()
                            : ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED,
                    quality.getExplanation());
        }

        // FAIL CLOSED: Unvalidated gate must never return ACCEPT.
        return GateResult.uncertain("Passed photographic quality check. Semantic leaf gate is unvalidated.");
    }
}
