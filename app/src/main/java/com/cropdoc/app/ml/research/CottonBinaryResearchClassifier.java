package com.cropdoc.app.ml.research;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import java.io.IOException;
import org.tensorflow.lite.Interpreter;

/**
 * Runner for the separately validated-integrity but biologically unvalidated cotton binary pilot.
 *
 * <p>Source labels have not been certified by a plant pathologist. Show the softmax value as a model score (not confidence), and do not persist its result as a
 * confirmed diagnosis or synchronize it to cloud storage.</p>
 *
 * <p>Pass the square 224x224 leaf-reticle crop used by the existing research pipeline.</p>
 */
public final class CottonBinaryResearchClassifier implements AutoCloseable {
    public static final String CLASS_BACTERIAL_BLIGHT = "cotton_bacterial_blight";
    public static final String CLASS_HEALTHY = "cotton_healthy";
    public static final String RESEARCH_DISCLAIMER =
            "AI MODEL PREDICTION — NOT A FIELD-VALIDATED DIAGNOSIS. "
                    + "Cotton labels have not received certified plant-pathologist validation.";

    private static final int WIDTH = 224;
    private static final int HEIGHT = 224;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final String[] CLASSES = {CLASS_BACTERIAL_BLIGHT, CLASS_HEALTHY};
    private final Interpreter interpreter;

    private CottonBinaryResearchClassifier(@NonNull Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    /** Load the model only after its Debug asset manifest, SHA-256, and tensor contract pass. */
    @NonNull
    public static CottonBinaryResearchClassifier create(@NonNull AssetManager assets) throws IOException {
        return new CottonBinaryResearchClassifier(CottonResearchModelLoader.loadValidated(assets));
    }

    /**
     * Runs inference on a pre-cropped reticle image. The returned softmax score is uncalibrated;
     * it is not a validated disease probability and must not be presented as such.
     */
    @NonNull
    public synchronized Prediction classify(@NonNull Bitmap reticleCrop224) throws IOException {
        if (reticleCrop224.isRecycled()) {
            throw new IOException("Input image bitmap has already been recycled.");
        }
        Bitmap scaled = reticleCrop224;
        boolean recycleScaled = false;
        if (reticleCrop224.getWidth() != WIDTH || reticleCrop224.getHeight() != HEIGHT) {
            scaled = Bitmap.createScaledBitmap(reticleCrop224, WIDTH, HEIGHT, true);
            recycleScaled = scaled != reticleCrop224;
        }

        long startNanos = System.nanoTime();
        try {
            int[] pixels = new int[WIDTH * HEIGHT];
            scaled.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
            float[][][][] input = new float[1][3][HEIGHT][WIDTH];
            for (int y = 0; y < HEIGHT; y++) {
                int row = y * WIDTH;
                for (int x = 0; x < WIDTH; x++) {
                    int argb = pixels[row + x];
                    float red = ((argb >> 16) & 0xFF) / 255.0f;
                    float green = ((argb >> 8) & 0xFF) / 255.0f;
                    float blue = (argb & 0xFF) / 255.0f;
                    input[0][0][y][x] = (red - MEAN[0]) / STD[0];
                    input[0][1][y][x] = (green - MEAN[1]) / STD[1];
                    input[0][2][y][x] = (blue - MEAN[2]) / STD[2];
                }
            }

            float[][] logits = new float[1][2];
            interpreter.run(input, logits);
            float max = Math.max(logits[0][0], logits[0][1]);
            if (!Float.isFinite(max) || !Float.isFinite(logits[0][0]) || !Float.isFinite(logits[0][1])) {
                throw new IOException("Cotton model returned a non-finite output.");
            }
            double e0 = Math.exp(logits[0][0] - max);
            double e1 = Math.exp(logits[0][1] - max);
            double sum = e0 + e1;
            float score0 = (float) (e0 / sum);
            float score1 = (float) (e1 / sum);
            int index = score0 >= score1 ? 0 : 1;
            long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            return new Prediction(CLASSES[index], index == 0 ? score0 : score1,
                    elapsedMs, logits[0][0], logits[0][1]);
        } finally {
            if (recycleScaled && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    @Override
    public synchronized void close() {
        interpreter.close();
    }

    public static final class Prediction {
        private final String classId;
        private final float modelScore;
        private final long inferenceTimeMs;
        private final float bacterialBlightLogit;
        private final float healthyLogit;

        private Prediction(String classId, float modelScore, long inferenceTimeMs,
                float bacterialBlightLogit, float healthyLogit) {
            this.classId = classId;
            this.modelScore = modelScore;
            this.inferenceTimeMs = inferenceTimeMs;
            this.bacterialBlightLogit = bacterialBlightLogit;
            this.healthyLogit = healthyLogit;
        }

        @NonNull public String getClassId() { return classId; }
        /** Softmax score only; not calibrated confidence or validated disease probability. */
        public float getModelScore() { return modelScore; }
        public long getInferenceTimeMs() { return inferenceTimeMs; }
        public float getBacterialBlightLogit() { return bacterialBlightLogit; }
        public float getHealthyLogit() { return healthyLogit; }
        @NonNull public String getDisclaimer() { return RESEARCH_DISCLAIMER; }
    }
}
