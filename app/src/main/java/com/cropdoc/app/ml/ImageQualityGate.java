package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Stage 1: Cheap Photographic Quality Sanity Gate.
 *
 * NOTE: This class checks ONLY basic photographic sanity:
 * 1. Minimum image dimensions (width and height >= 50 pixels)
 * 2. Extreme underexposure (mean luminance < 15 lux, pitch black)
 * 3. Extreme overexposure (mean luminance > 245 lux, white washout)
 *
 * It is NOT a leaf detector, crop recognizer, or human/face detector.
 * Semantic leaf-vs-nonleaf discrimination is handled strictly by the LeafGate stage (G2).
 */
public final class ImageQualityGate {

    public static final float MIN_LUMINANCE = 15.0f;  // Extreme underexposure (pitch black)
    public static final float MAX_LUMINANCE = 245.0f; // Extreme overexposure (pure white washout)
    public static final int MIN_DIMENSION = 50;

    public enum QualityDecision {
        ACCEPT,
        REJECT
    }

    public static final class QualityResult {
        @NonNull private final QualityDecision decision;
        @Nullable private final ClassificationOutcome.RejectionReason rejectionReason;
        @NonNull private final String explanation;

        private QualityResult(
                @NonNull QualityDecision decision,
                @Nullable ClassificationOutcome.RejectionReason rejectionReason,
                @NonNull String explanation) {
            this.decision = Objects.requireNonNull(decision, "decision");
            this.rejectionReason = rejectionReason;
            this.explanation = Objects.requireNonNull(explanation, "explanation");
        }

        @NonNull
        public static QualityResult accept(@NonNull String explanation) {
            return new QualityResult(QualityDecision.ACCEPT, null, explanation);
        }

        @NonNull
        public static QualityResult reject(
                @NonNull ClassificationOutcome.RejectionReason reason,
                @NonNull String explanation) {
            return new QualityResult(QualityDecision.REJECT, reason, explanation);
        }

        public boolean isAccepted() {
            return decision == QualityDecision.ACCEPT;
        }

        @NonNull
        public QualityDecision getDecision() {
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
            return "QualityResult{" + decision + ", reason=" + rejectionReason
                    + ", explanation='" + explanation + "'}";
        }
    }

    @NonNull
    public QualityResult evaluate(
            @NonNull Bitmap preprocessedBitmap,
            @NonNull ByteBuffer tensorBuffer) {
        if (preprocessedBitmap.getWidth() < MIN_DIMENSION || preprocessedBitmap.getHeight() < MIN_DIMENSION) {
            return QualityResult.reject(
                    ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED,
                    "Bitmap dimensions too small for analysis (" + preprocessedBitmap.getWidth()
                            + "x" + preprocessedBitmap.getHeight() + ").");
        }

        tensorBuffer.rewind();
        int totalPixels = ModelConfig.PIXEL_COUNT;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;

        for (int i = 0; i < totalPixels; i++) {
            int r = tensorBuffer.get() & 0xFF;
            int g = tensorBuffer.get() & 0xFF;
            int b = tensorBuffer.get() & 0xFF;
            sumR += r;
            sumG += g;
            sumB += b;
        }
        tensorBuffer.rewind();

        float meanR = (float) sumR / totalPixels;
        float meanG = (float) sumG / totalPixels;
        float meanB = (float) sumB / totalPixels;
        float meanLuminance = (0.299f * meanR) + (0.587f * meanG) + (0.114f * meanB);

        if (meanLuminance < MIN_LUMINANCE) {
            return QualityResult.reject(
                    ClassificationOutcome.RejectionReason.UNDER_EXPOSED,
                    "Image is severely underexposed (mean luminance " + meanLuminance
                            + " < " + MIN_LUMINANCE + "). Please retake in daylight.");
        }

        if (meanLuminance > MAX_LUMINANCE) {
            return QualityResult.reject(
                    ClassificationOutcome.RejectionReason.OVER_EXPOSED,
                    "Image is severely overexposed (mean luminance " + meanLuminance
                            + " > " + MAX_LUMINANCE + "). Please avoid direct flash glare.");
        }

        return QualityResult.accept("Passed photographic quality sanity checks (mean luminance "
                + meanLuminance + ").");
    }
}
