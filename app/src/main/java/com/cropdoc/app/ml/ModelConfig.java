package com.cropdoc.app.ml;

/**
 * Immutable constants for the LiteRT 2.3.0 leaf disease classifier and OOD gating thresholds.
 */
public final class ModelConfig {

    public static final int INPUT_WIDTH = 224;
    public static final int INPUT_HEIGHT = 224;
    public static final int COLOR_CHANNELS = 3;
    public static final int PIXEL_COUNT = INPUT_WIDTH * INPUT_HEIGHT;
    public static final int BYTE_BUFFER_SIZE = PIXEL_COUNT * COLOR_CHANNELS;
    public static final int NUM_CLASSES = 16;
    public static final int OOD_CLASS_INDEX = 15;
    public static final int NUM_CPU_THREADS = 4;

    public static final float TEMPERATURE_SCALE = 1.45f;
    public static final float CONFIDENCE_THRESHOLD = 0.65f;
    public static final float MAX_NORMALIZED_ENTROPY = 0.55f;
    public static final float MIN_LEAF_PIXEL_RATIO = 0.18f;
    public static final float RETICLE_CROP_RATIO = 0.75f;

    public static final String BUNDLED_MODEL_ASSET = "models/cropdoc_leaf_v1_int8.tflite";
    public static final String LABELS_ASSET = "models/labels.txt";
    public static final String DEFAULT_MODEL_VERSION = "1.0.0-int8";
    public static final String OOD_CLASS_LABEL = "background_not_a_leaf";

    private ModelConfig() {
        // Constants only
    }
}
