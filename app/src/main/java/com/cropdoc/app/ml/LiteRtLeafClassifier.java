package com.cropdoc.app.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.data.remote.ModelUpdateManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.tensorflow.lite.Interpreter;

/**
 * Production LiteRT classifier using the Java Interpreter API with XNNPACK 4-thread CPU
 * execution, atomic OTA model+labels pairing, load-time cryptographic manifest verification,
 * and fail-closed leaf-gating architecture.
 *
 * Pipeline sequence:
 * 1. ImageQualityGate (cheap exposure/dimension sanity)
 * 2. LeafGate (semantic leaf-vs-nonleaf gate, FAIL CLOSED)
 * 3. ModelValidator (manifest and shape integrity verification)
 * 4. LiteRT Disease Classifier (MobileNetV3 with monotonic clock timing)
 * 5. ConfidenceCalibrator (non-diagnostic class rejection and entropy calculation)
 */
public final class LiteRtLeafClassifier implements LeafClassifier {

    private final Object lock = new Object();

    @NonNull
    private final Context appContext;

    @NonNull
    private final ModelUpdateManager modelUpdateManager;

    @NonNull
    private final ImageQualityGate qualityGate;

    @NonNull
    private final LeafGate leafGate;

    @NonNull
    private final ByteBuffer reusableInputBuffer;

    @NonNull
    private final int[] reusablePixelArray;

    @NonNull
    private List<String> labels;

    @NonNull
    private float[][] reusableOutputLogits;

    @Nullable
    private Interpreter interpreter;

    @Nullable
    private ModelValidator.ValidationResult modelValidationResult;

    private boolean initializationAttempted;

    @NonNull
    private String activeModelVersion = ModelConfig.DEFAULT_MODEL_VERSION;

    public LiteRtLeafClassifier(
            @NonNull Context context,
            @NonNull ModelUpdateManager modelUpdateManager) {
        this(context, modelUpdateManager, new ImageQualityGate(), new DefaultLeafGate());
    }

    public LiteRtLeafClassifier(
            @NonNull Context context,
            @NonNull ModelUpdateManager modelUpdateManager,
            @NonNull LeafGate leafGate) {
        this(context, modelUpdateManager, new ImageQualityGate(), leafGate);
    }

    public LiteRtLeafClassifier(
            @NonNull Context context,
            @NonNull ModelUpdateManager modelUpdateManager,
            @NonNull ImageQualityGate qualityGate,
            @NonNull LeafGate leafGate) {
        this.appContext = context.getApplicationContext();
        this.modelUpdateManager = Objects.requireNonNull(modelUpdateManager, "modelUpdateManager");
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.leafGate = Objects.requireNonNull(leafGate, "leafGate");
        this.reusableInputBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE);
        this.reusableInputBuffer.order(ByteOrder.nativeOrder());
        this.reusablePixelArray = new int[ModelConfig.PIXEL_COUNT];
        this.labels = loadBundledLabels(appContext);
        int dynamicClasses = resolveDynamicClassCount(this.labels, 0);
        this.reusableOutputLogits = new float[1][dynamicClasses];
    }

    /**
     * Package-private test constructor enabling direct injection of mock/stub components.
     */
    LiteRtLeafClassifier(
            @NonNull ImageQualityGate qualityGate,
            @NonNull LeafGate leafGate,
            @Nullable Interpreter interpreter,
            @Nullable ModelValidator.ValidationResult validationResult,
            @NonNull List<String> labels,
            @NonNull String modelVersion) {
        this.appContext = null;
        this.modelUpdateManager = null;
        this.qualityGate = Objects.requireNonNull(qualityGate, "qualityGate");
        this.leafGate = Objects.requireNonNull(leafGate, "leafGate");
        this.interpreter = interpreter;
        this.modelValidationResult = validationResult;
        this.labels = new ArrayList<>(labels);
        this.activeModelVersion = modelVersion;
        this.initializationAttempted = true;
        this.reusableInputBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE);
        this.reusableInputBuffer.order(ByteOrder.nativeOrder());
        this.reusablePixelArray = new int[ModelConfig.PIXEL_COUNT];
        int dynamicClasses = resolveDynamicClassCount(this.labels, 0);
        this.reusableOutputLogits = new float[1][dynamicClasses];
    }

    public static int resolveDynamicClassCount(
            @NonNull List<String> loadedLabels, int tensorClasses) {
        int fromLabels = loadedLabels.isEmpty() ? ModelConfig.NUM_CLASSES : loadedLabels.size();
        return Math.max(ModelConfig.NUM_CLASSES, Math.max(fromLabels, tensorClasses));
    }

    public static boolean isLabelTensorCountConsistent(
            @NonNull List<String> candidateLabels, int tensorClasses) {
        return !candidateLabels.isEmpty() && tensorClasses > 0
                && candidateLabels.size() == tensorClasses;
    }

    @NonNull
    @Override
    public ClassificationOutcome classify(@NonNull Bitmap rawBitmap) {
        synchronized (lock) {
            ensureInterpreterInitialized();
            ClassificationOutcome validationErr = checkValidationFailure();
            if (validationErr != null) { return validationErr; }

            Bitmap prepared = LeafPreprocessor.cropReticleAndResize(rawBitmap, reusablePixelArray);
            try {
                float leafRatio = LeafPreprocessor.populateByteBufferAndComputeLeafRatio(
                        prepared, reusableInputBuffer, reusablePixelArray);

                ClassificationOutcome qualityErr = evaluateQualityStage(prepared);
                if (qualityErr != null) { return qualityErr; }

                ClassificationOutcome gateOutcome = evaluateLeafGateStage(prepared, reusableInputBuffer);
                if (gateOutcome != null) { return gateOutcome; }

                if (interpreter == null) {
                    return new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.INTERPRETER_INIT_FAILED);
                }

                long durationMs;
                try {
                    durationMs = runInference(interpreter);
                } catch (Throwable t) {
                    return new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.INFERENCE_EXCEPTION);
                }

                if (LeafSpectralAnalyzer.isAllZeroOrUniform(reusableOutputLogits[0])) {
                    return new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.UNIFORM_OR_DEGENERATE_OUTPUT);
                }

                return ConfidenceCalibrator.evaluateLogitsOutcome(
                        reusableOutputLogits[0], labels, resolveNonDiagnosticClasses(),
                        activeModelVersion, durationMs, leafRatio);
            } finally {
                if (prepared != rawBitmap && !prepared.isRecycled()) {
                    prepared.recycle();
                }
            }
        }
    }

    @Nullable
    private ClassificationOutcome checkValidationFailure() {
        if (modelValidationResult != null && !modelValidationResult.isValid()) {
            ClassificationOutcome.UnavailableReason reason = modelValidationResult.getReason();
            return new ClassificationOutcome.ModelUnavailable(
                    reason != null ? reason : ClassificationOutcome.UnavailableReason.INTERPRETER_INIT_FAILED);
        }
        return null;
    }

    private long runInference(@NonNull Interpreter interp) throws Exception {
        Arrays.fill(reusableOutputLogits[0], 0f);
        long t0 = System.nanoTime();
        interp.run(reusableInputBuffer, reusableOutputLogits);
        return Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
    }

    @NonNull
    private Set<String> resolveNonDiagnosticClasses() {
        return (modelValidationResult != null && modelValidationResult.getManifest() != null)
                ? modelValidationResult.getManifest().getNonDiagnosticClasses()
                : Collections.singleton(ModelConfig.OOD_CLASS_LABEL);
    }

    @Nullable
    private ClassificationOutcome evaluateQualityStage(@NonNull Bitmap prepared) {
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(prepared, reusableInputBuffer);
        if (!quality.isAccepted()) {
            ClassificationOutcome.RejectionReason reason = quality.getRejectionReason();
            return new ClassificationOutcome.Rejected(
                    reason != null ? reason : ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED);
        }
        return null;
    }

    @Nullable
    private ClassificationOutcome evaluateLeafGateStage(
            @NonNull Bitmap prepared,
            @NonNull ByteBuffer inputBuffer) {
        if (leafGate.getState() != LeafGate.GateState.VALIDATED) {
            return new ClassificationOutcome.ModelUnavailable(
                    leafGate.getState() == LeafGate.GateState.NOT_PRESENT
                            ? ClassificationOutcome.UnavailableReason.GATE_UNAVAILABLE
                            : ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED);
        }

        LeafGate.GateResult gateResult = leafGate.evaluate(prepared, inputBuffer);
        LeafGate.GateDecision decision = gateResult.getDecision();
        switch (decision) {
            case ACCEPT:
                if (leafGate.getState() != LeafGate.GateState.VALIDATED) {
                    return new ClassificationOutcome.ModelUnavailable(
                            ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED);
                }
                return null;
            case REJECT:
                ClassificationOutcome.RejectionReason reason = gateResult.getRejectionReason();
                return new ClassificationOutcome.Rejected(
                        reason != null ? reason : ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED);
            case UNCERTAIN:
            default:
                return new ClassificationOutcome.ModelUnavailable(
                        ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED);
        }
    }

    @NonNull
    @Override
    public String getModelVersion() {
        return activeModelVersion;
    }

    @Nullable
    public ModelValidator.ValidationResult getModelValidationResult() {
        synchronized (lock) {
            return modelValidationResult;
        }
    }

    @NonNull
    public LeafGate getLeafGate() {
        synchronized (lock) {
            return leafGate;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (interpreter != null) {
                try {
                    interpreter.close();
                } catch (Throwable ignored) {
                    // Safe close
                }
                interpreter = null;
            }
            initializationAttempted = false;
        }
    }

    private void ensureInterpreterInitialized() {
        if (initializationAttempted) {
            return;
        }
        initializationAttempted = true;
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(ModelConfig.NUM_CPU_THREADS);
        options.setUseXNNPACK(true);
        if (tryInitializeOtaPair(options)) {
            return;
        }
        initializeBundledPair(options);
    }

    private boolean tryInitializeOtaPair(@NonNull Interpreter.Options options) {
        File otaModel = modelUpdateManager.getVerifiedOtaModelFile();
        File otaLabels = modelUpdateManager.getVerifiedOtaLabelsFile();
        if (otaModel == null || otaModel.length() < ModelValidator.MIN_REAL_MODEL_BYTES || otaLabels == null) {
            return false;
        }
        try (FileInputStream modelFis = new FileInputStream(otaModel);
             FileChannel channel = modelFis.getChannel();
             FileInputStream labelsFis = new FileInputStream(otaLabels)) {
            List<String> candidateLabels = readLabelsStream(labelsFis);
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            Interpreter candidate = new Interpreter(buffer, options);

            File manifestFile = new File(otaModel.getParentFile(), "model_manifest.json");
            String manifestJson = manifestFile.exists()
                    ? readStreamToString(new FileInputStream(manifestFile))
                    : null;

            ModelValidator.ValidationResult valResult = ModelValidator.validateModelWithManifest(
                    otaModel.length(), buffer, new FileInputStream(otaModel), candidate, candidateLabels,
                    modelUpdateManager.getActiveModelVersion(), manifestJson, "ota:" + otaModel.getAbsolutePath());

            if (!valResult.isValid()) {
                candidate.close();
                modelUpdateManager.clearInvalidOtaFiles();
                return false;
            }

            int tensorClasses = extractOutputTensorClasses(candidate);
            this.interpreter = candidate;
            this.labels = candidateLabels;
            this.activeModelVersion = modelUpdateManager.getActiveModelVersion();
            this.reusableOutputLogits = new float[1][tensorClasses];
            this.modelValidationResult = valResult;
            return true;
        } catch (Throwable ignored) {
            modelUpdateManager.clearInvalidOtaFiles();
            return false;
        }
    }

    private void initializeBundledPair(@NonNull Interpreter.Options options) {
        this.labels = loadBundledLabels(appContext);
        this.activeModelVersion = ModelConfig.DEFAULT_MODEL_VERSION;
        String manifestJson = loadBundledManifest(appContext);

        try (AssetFileDescriptor afd = appContext.getAssets().openFd(ModelConfig.BUNDLED_MODEL_ASSET);
             FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
             FileChannel channel = fis.getChannel()) {
            long length = afd.getDeclaredLength();
            MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), length);
            Interpreter candidate = createCandidateInterpreter(buf, options);
            InputStream modelStream = appContext.getAssets().open(ModelConfig.BUNDLED_MODEL_ASSET);
            ModelValidator.ValidationResult valResult = ModelValidator.validateModelWithManifest(
                    length, buf, modelStream, candidate, labels, activeModelVersion,
                    manifestJson, "bundled:" + ModelConfig.BUNDLED_MODEL_ASSET);

            if (!valResult.isValid()) {
                if (candidate != null) { candidate.close(); }
                this.modelValidationResult = valResult;
                this.interpreter = null;
                return;
            }
            applyValidatedInterpreter(candidate, valResult);
        } catch (Throwable e) {
            this.modelValidationResult = ModelValidator.ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INTERPRETER_INIT_FAILED,
                    "Failed to initialize bundled model interpreter: " + e.getMessage());
            this.interpreter = null;
        }
    }

    @Nullable
    private static Interpreter createCandidateInterpreter(
            @NonNull ByteBuffer buf,
            @NonNull Interpreter.Options opts) {
        try {
            return new Interpreter(buf, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyValidatedInterpreter(
            @NonNull Interpreter candidate,
            @NonNull ModelValidator.ValidationResult valResult) {
        this.interpreter = candidate;
        this.modelValidationResult = valResult;
        int tensorClasses = extractOutputTensorClasses(candidate);
        int target = resolveDynamicClassCount(labels, tensorClasses);
        if (target != reusableOutputLogits[0].length) {
            reusableOutputLogits = new float[1][target];
        }
    }

    private static int extractOutputTensorClasses(@NonNull Interpreter candidate) {
        if (candidate.getOutputTensorCount() <= 0) {
            return ModelConfig.NUM_CLASSES;
        }
        int[] shape = candidate.getOutputTensor(0).shape();
        return shape.length >= 2 ? shape[1] : shape[0];
    }

    @Nullable
    private static String loadBundledManifest(@NonNull Context context) {
        try (InputStream is = context.getAssets().open("models/cropdoc_leaf_v1_manifest.json")) {
            return readStreamToString(is);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static List<String> loadBundledLabels(@NonNull Context context) {
        try (InputStream is = context.getAssets().open(ModelConfig.LABELS_ASSET)) {
            return readLabelsStream(is);
        } catch (Throwable ignored) {
            return new ArrayList<>(ModelConfig.NUM_CLASSES);
        }
    }

    @NonNull
    public static List<String> readLabelsStream(@NonNull InputStream is) throws IOException {
        List<String> loaded = new ArrayList<>(ModelConfig.NUM_CLASSES);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    loaded.add(trimmed);
                }
            }
        }
        return loaded;
    }

    @NonNull
    private static String readStreamToString(@NonNull InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
