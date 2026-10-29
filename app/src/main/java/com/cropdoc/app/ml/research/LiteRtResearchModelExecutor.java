package com.cropdoc.app.ml.research;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

/**
 * Concrete LiteRT / TFLite research model executor implementing fail-closed error handling.
 *
 * Encapsulates single- or multi-threaded CPU interpreter execution, input tensor size
 * validation, monotonic latency tracking, and graceful handling of missing native runtimes.
 */
public final class LiteRtResearchModelExecutor implements ResearchModelExecutor {

    public static final int EXPECTED_NCHW_BYTES = 1 * 3 * 224 * 224 * 4; // 602,112 bytes

    private final Object lock = new Object();
    private final int outputClasses;
    @Nullable private final String modelIdentifier;
    @Nullable private Interpreter interpreter;
    @Nullable private String loadErrorMessage;
    private long lastExecutionLatencyMs = -1L;

    public LiteRtResearchModelExecutor(
            @NonNull Interpreter interpreter,
            int outputClasses,
            @Nullable String modelIdentifier) {
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.outputClasses = outputClasses;
        this.modelIdentifier = modelIdentifier;
        this.loadErrorMessage = null;
    }

    private LiteRtResearchModelExecutor(
            @Nullable Interpreter interpreter,
            int outputClasses,
            @Nullable String modelIdentifier,
            @Nullable String loadErrorMessage) {
        this.interpreter = interpreter;
        this.outputClasses = outputClasses;
        this.modelIdentifier = modelIdentifier;
        this.loadErrorMessage = loadErrorMessage;
    }

    /**
     * Attempts to create an executor from a direct model ByteBuffer.
     */
    @NonNull
    public static LiteRtResearchModelExecutor fromBuffer(
            @NonNull ByteBuffer buffer,
            int outputClasses,
            @Nullable Interpreter.Options options,
            @Nullable String modelIdentifier) {
        try {
            Interpreter.Options opt = options != null ? options : new Interpreter.Options();
            Interpreter interp = new Interpreter(buffer, opt);
            return new LiteRtResearchModelExecutor(interp, outputClasses, modelIdentifier);
        } catch (Throwable t) {
            return new LiteRtResearchModelExecutor(
                    null, outputClasses, modelIdentifier, t.getMessage());
        }
    }

    /**
     * Attempts to create an executor from a local model File.
     */
    @NonNull
    public static LiteRtResearchModelExecutor fromFile(
            @NonNull File modelFile,
            int outputClasses,
            @Nullable Interpreter.Options options) {
        try {
            Interpreter.Options opt = options != null ? options : new Interpreter.Options();
            Interpreter interp = new Interpreter(modelFile, opt);
            return new LiteRtResearchModelExecutor(interp, outputClasses, modelFile.getName());
        } catch (Throwable t) {
            return new LiteRtResearchModelExecutor(
                    null, outputClasses, modelFile.getName(), t.getMessage());
        }
    }

    /**
     * Attempts to create an executor from an Android application asset.
     */
    @NonNull
    public static LiteRtResearchModelExecutor fromAsset(
            @NonNull Context context,
            @NonNull String assetPath,
            int outputClasses,
            @Nullable Interpreter.Options options) {
        // The main Scan screen intentionally uses this integrity-checked model bundle in all
        // build variants. Research Demo navigation remains Debug-only, but crop-selected Scan
        // outputs are explicitly labelled preliminary and are never persisted as diagnoses.
        try {
            Interpreter.Options opt = options != null ? options : new Interpreter.Options();
            opt.setNumThreads(2);
            opt.setUseXNNPACK(false);

            try (InputStream is = context.getAssets().open(assetPath)) {
                int size = is.available();
                byte[] bytes = new byte[size];
                int readTotal = 0;
                while (readTotal < size) {
                    int r = is.read(bytes, readTotal, size - readTotal);
                    if (r == -1) {
                        break;
                    }
                    readTotal += r;
                }
                ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length)
                        .order(ByteOrder.nativeOrder());
                direct.put(bytes);
                direct.rewind();
                Interpreter interp = new Interpreter(direct, opt);
                return new LiteRtResearchModelExecutor(interp, outputClasses, assetPath);
            }
        } catch (Throwable t) {
            return new LiteRtResearchModelExecutor(
                    null, outputClasses, assetPath, t.getMessage());
        }
    }

    @NonNull
    @Override
    public float[] execute(@NonNull ByteBuffer inputBuffer) throws Exception {
        synchronized (lock) {
            if (interpreter == null) {
                throw new IllegalStateException(
                        "Interpreter is not available: " + loadErrorMessage);
            }
            Objects.requireNonNull(inputBuffer, "inputBuffer");
            if (inputBuffer.remaining() < EXPECTED_NCHW_BYTES) {
                throw new IllegalArgumentException("Input buffer remaining ("
                        + inputBuffer.remaining() + " bytes) is smaller than required "
                        + EXPECTED_NCHW_BYTES + " bytes.");
            }

            float[][] output = new float[1][outputClasses];
            long startNs = System.nanoTime();
            interpreter.run(inputBuffer, output);
            lastExecutionLatencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            return output[0];
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (lock) {
            return interpreter != null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
        }
    }

    public long getLastExecutionLatencyMs() {
        synchronized (lock) {
            return lastExecutionLatencyMs;
        }
    }

    @Nullable
    public String getLoadErrorMessage() {
        synchronized (lock) {
            return loadErrorMessage;
        }
    }

    @Nullable
    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public int getOutputClasses() {
        return outputClasses;
    }

    @Nullable
    public int[] getInputTensorShape() {
        synchronized (lock) {
            return interpreter != null ? interpreter.getInputTensor(0).shape() : null;
        }
    }

    @Nullable
    public DataType getInputTensorDataType() {
        synchronized (lock) {
            return interpreter != null ? interpreter.getInputTensor(0).dataType() : null;
        }
    }

    @Nullable
    public int[] getOutputTensorShape() {
        synchronized (lock) {
            return interpreter != null ? interpreter.getOutputTensor(0).shape() : null;
        }
    }

    @Nullable
    public DataType getOutputTensorDataType() {
        synchronized (lock) {
            return interpreter != null ? interpreter.getOutputTensor(0).dataType() : null;
        }
    }
}
