package com.cropdoc.app.ui.research;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.cropdoc.app.R;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.LeafPreprocessor;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.ml.policy.InferenceDecisionPolicy;
import com.cropdoc.app.ml.policy.InferencePolicyConfig;
import com.cropdoc.app.ml.research.CottonBinaryResearchClassifier;
import com.cropdoc.app.ml.research.LiteRtResearchModelExecutor;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import com.cropdoc.app.ml.research.ResearchInferenceHarness;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-only ViewModel for the existing six-class rice/groundnut demo and a separate cotton pilot.
 * Cotton outputs are research-only and never enter the production scan persistence path.
 */
public final class ResearchDemoViewModel extends ViewModel {

    public enum ResearchMode {
        SIX_CLASS_RICE_GROUNDNUT,
        COTTON_BINARY_PILOT
    }

    private static final String TAG = "ResearchDemo";
    private static final String DOMAIN_ASSET = "research_models/crop_domain_gate_fp32.tflite";
    private static final String DISEASE_ASSET = "research_models/unified_six_class_fp32.tflite";
    private static final String SAMPLE_RICE_ASSET = "research_models/sample_rice_leaf.jpg";
    private static final String SAMPLE_SOIL_ASSET = "research_models/sample_non_leaf_soil.jpg";

    @NonNull private final Context appContext;
    @NonNull private final AppExecutors executors;
    @NonNull private final MutableLiveData<ResearchDemoState> state = new MutableLiveData<>();
    @NonNull private final AtomicBoolean isExecuting = new AtomicBoolean(false);
    @NonNull private final AtomicLong requestIdCounter = new AtomicLong(0L);
    @NonNull private final ResearchInferenceHarness harness;
    @NonNull private final ImageQualityGate qualityGate;
    @NonNull private final ByteBuffer byteBuffer;
    @NonNull private final ByteBuffer cottonRgbBuffer;
    @NonNull private final int[] cottonPixels = new int[ModelConfig.PIXEL_COUNT];
    private final boolean sixClassModelsAvailable;
    @Nullable private LiteRtResearchModelExecutor domainExecutor;
    @Nullable private LiteRtResearchModelExecutor diseaseExecutor;
    @Nullable private CottonBinaryResearchClassifier cottonClassifier;
    @Nullable private String cottonLoadError;
    private volatile ResearchMode researchMode = ResearchMode.SIX_CLASS_RICE_GROUNDNUT;

    public ResearchDemoViewModel(@NonNull Context context, @NonNull AppExecutors executors) {
        if (!com.cropdoc.app.BuildConfig.DEBUG) {
            throw new IllegalStateException("ResearchDemoViewModel prohibited in release builds.");
        }
        this.executors = Objects.requireNonNull(executors, "executors");
        this.appContext = context.getApplicationContext();
        this.domainExecutor = LiteRtResearchModelExecutor.fromAsset(appContext, DOMAIN_ASSET, 3, null);
        this.diseaseExecutor = LiteRtResearchModelExecutor.fromAsset(appContext, DISEASE_ASSET, 6, null);
        this.sixClassModelsAvailable = domainExecutor != null && domainExecutor.isAvailable()
                && diseaseExecutor != null && diseaseExecutor.isAvailable();

        try {
            this.cottonClassifier = CottonBinaryResearchClassifier.create(appContext.getAssets());
        } catch (Throwable e) {
            this.cottonClassifier = null;
            this.cottonLoadError = e.getMessage();
            logDebug(TAG, "Cotton pilot not available: " + cottonLoadError);
        }

        this.qualityGate = new ImageQualityGate();
        InferenceDecisionPolicy policy =
                new InferenceDecisionPolicy(InferencePolicyConfig.createWithCropDomainGate());
        this.harness = new ResearchInferenceHarness(qualityGate, policy, domainExecutor, diseaseExecutor);
        this.byteBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE);
        this.cottonRgbBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE)
                .order(ByteOrder.nativeOrder());
        this.state.setValue(ResearchDemoState.noImageSelected(
                currentModeModelsAvailable(), currentModelUnavailableMessageOrNull()));
    }

    @NonNull
    public LiveData<ResearchDemoState> getState() { return state; }

    @NonNull
    public ResearchMode getResearchMode() { return researchMode; }

    public boolean isCottonMode() { return researchMode == ResearchMode.COTTON_BINARY_PILOT; }

    public boolean currentModeModelsAvailable() {
        return isCottonMode() ? cottonClassifier != null : sixClassModelsAvailable;
    }

    @NonNull
    public String getCurrentModelUnavailableMessage() {
        String base;
        if (isCottonMode()) {
            base = getStringRes(R.string.research_cotton_model_unavailable_warning,
                    "Cotton research model is not available. Export the cotton checkpoint to TFLite and add the model and manifest to Debug assets.");
            if (cottonLoadError != null && !cottonLoadError.trim().isEmpty()) {
                return base + "\nDetails: " + cottonLoadError;
            }
            return base;
        }
        return getStringRes(R.string.research_models_unavailable_warning,
                "Research models are unavailable on this runtime.");
    }

    @Nullable
    private String currentModelUnavailableMessageOrNull() {
        return currentModeModelsAvailable() ? null : getCurrentModelUnavailableMessage();
    }

    public void setResearchMode(@NonNull ResearchMode mode) {
        if (mode == researchMode) {
            return;
        }
        researchMode = mode;
        // Clear any previously selected preview when switching models. A rice sample or old
        // preview must not accidentally be evaluated by the cotton classifier (or vice versa).
        state.setValue(ResearchDemoState.noImageSelected(
                currentModeModelsAvailable(), currentModelUnavailableMessageOrNull()));
    }

    public void analyzeUri(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        if (uri == null) {
            postFailure(requestIdCounter.incrementAndGet(), null,
                    getStringRes(R.string.research_error_no_image_selected, "No image selected"));
            return;
        }
        final long reqId = requestIdCounter.incrementAndGet();
        isExecuting.set(true);
        state.setValue(ResearchDemoState.analyzing(null, currentModeModelsAvailable()));
        executors.diskIo().execute(() -> processUri(resolver, uri, reqId));
    }

    private void processUri(@NonNull ContentResolver resolver, @NonNull Uri uri, long reqId) {
        File temp = new File(appContext.getCacheDir(), "research_pick_" + reqId + ".jpg");
        if (!ResearchImageDecoder.copyUriToFile(resolver, uri, temp)) {
            if (reqId == requestIdCounter.get()) {
                postFailure(reqId, null,
                        getStringRes(R.string.research_error_open_image, "Failed to read image stream"));
            }
            return;
        }
        Bitmap square224 = ResearchImageDecoder.decodeAndCropSquare(temp);
        if (square224 == null) {
            if (reqId == requestIdCounter.get()) {
                postFailure(reqId, null,
                        getStringRes(R.string.research_error_decode_failed, "Image could not be decoded"));
            }
            return;
        }
        if (reqId != requestIdCounter.get()) {
            if (!square224.isRecycled()) {
                square224.recycle();
            }
            return;
        }
        logDebug(TAG, "Decoded: " + square224.getWidth() + "x" + square224.getHeight());
        state.postValue(ResearchDemoState.analyzing(square224, currentModeModelsAvailable()));
        executors.mlInference().execute(() -> {
            if (reqId == requestIdCounter.get()) {
                executeOnBitmap(square224, reqId);
            }
        });
    }

    public void analyzeBitmap(@NonNull Bitmap bitmap) {
        final long reqId = requestIdCounter.incrementAndGet();
        isExecuting.set(true);
        state.setValue(ResearchDemoState.analyzing(bitmap, currentModeModelsAvailable()));
        executors.mlInference().execute(() -> executeOnBitmap(bitmap, reqId));
    }

    public void loadSampleRiceLeaf() {
        // This sample belongs to the existing rice/groundnut pipeline, not the cotton pilot.
        setResearchMode(ResearchMode.SIX_CLASS_RICE_GROUNDNUT);
        loadSampleFromAsset(SAMPLE_RICE_ASSET);
    }

    public void loadSampleNonLeafSoil() {
        loadSampleFromAsset(SAMPLE_SOIL_ASSET);
    }

    private void loadSampleFromAsset(@NonNull String assetPath) {
        final long reqId = requestIdCounter.incrementAndGet();
        isExecuting.set(true);
        state.setValue(ResearchDemoState.analyzing(null, currentModeModelsAvailable()));
        executors.diskIo().execute(() -> {
            Bitmap sample = null;
            String sampleError = null;
            try (InputStream is = appContext.getAssets().open(assetPath)) {
                sample = BitmapFactory.decodeStream(is);
            } catch (Throwable t) {
                sampleError = t.getMessage();
            }
            final Bitmap image = sample;
            if (reqId != requestIdCounter.get()) {
                if (image != null && !image.isRecycled()) {
                    image.recycle();
                }
                return;
            }
            // Never substitute a synthetic solid-color bitmap: a sample button must load the real
            // bundled sample or report a clear error instead of creating a misleading prediction.
            if (image == null) {
                postFailure(reqId, null, getStringRes(
                        R.string.research_sample_asset_missing,
                        "Bundled research sample is missing or could not be decoded. No inference was run."));
                logDebug(TAG, "Could not load sample asset " + assetPath + ": " + sampleError);
                return;
            }
            state.postValue(ResearchDemoState.analyzing(image, currentModeModelsAvailable()));
            executors.mlInference().execute(() -> {
                if (reqId == requestIdCounter.get()) {
                    executeOnBitmap(image, reqId);
                }
            });
        });
    }

    private void executeOnBitmap(@NonNull Bitmap raw, long reqId) {
        Bitmap scaled = null;
        try {
            scaled = (raw.getWidth() == 224 && raw.getHeight() == 224)
                    ? raw : Bitmap.createScaledBitmap(raw, 224, 224, true);
            long startNs = System.nanoTime();

            if (researchMode == ResearchMode.COTTON_BINARY_PILOT) {
                executeCottonPilot(scaled, reqId, startNs);
                return;
            }

            byteBuffer.rewind();
            ResearchHarnessOutcome outcome = harness.processImage(scaled, byteBuffer);
            long totalLatency = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            boolean domainRan = outcome.getTerminalStage() != ResearchHarnessOutcome.Stage.QUALITY_CHECK
                    && outcome.getTerminalStage() != ResearchHarnessOutcome.Stage.FOLIAR_GATE;
            long domLat = domainRan && domainExecutor != null
                    ? domainExecutor.getLastExecutionLatencyMs() : -1L;
            boolean diseaseRan = (outcome.getTerminalStage() == ResearchHarnessOutcome.Stage.DECISION_POLICY);
            long disLat = diseaseRan && diseaseExecutor != null
                    ? diseaseExecutor.getLastExecutionLatencyMs() : -1L;

            if (reqId == requestIdCounter.get()) {
                state.postValue(ResearchDemoState.completed(
                        scaled, outcome, totalLatency, domLat, disLat, sixClassModelsAvailable));
            }
        } catch (Throwable t) {
            logError(TAG, "Inference error: " + t.getMessage(), t);
            if (reqId == requestIdCounter.get()) {
                String prefix = getStringRes(R.string.research_error_inference_failed, "Research inference failed");
                postFailure(reqId, scaled != null ? scaled : raw, prefix + ": " + t.getMessage());
            }
        } finally {
            isExecuting.set(false);
        }
    }

    private void executeCottonPilot(@NonNull Bitmap bitmap224, long reqId, long startNs) throws IOException {
        if (cottonClassifier == null) {
            throw new IOException(getCurrentModelUnavailableMessage());
        }

        // Reuse the same basic exposure and foliage-coverage gates before running the separate
        // binary pilot. This gate does NOT verify that the input is cotton; the user explicitly
        // selected Cotton Pilot, so the result remains clearly experimental.
        cottonRgbBuffer.rewind();
        float leafRatio = LeafPreprocessor.populateByteBufferAndComputeLeafRatio(
                bitmap224, cottonRgbBuffer, cottonPixels);
        ImageQualityGate.QualityResult quality = qualityGate.evaluate(bitmap224, cottonRgbBuffer);
        if (!quality.isAccepted()) {
            throw new IOException("Cotton pilot stopped at image-quality gate: " + quality.getExplanation());
        }
        if (leafRatio < ModelConfig.MIN_LEAF_PIXEL_RATIO) {
            throw new IOException(String.format(Locale.ROOT,
                    "Cotton pilot stopped at foliage-coverage gate (%.1f%% detected; %.1f%% required).",
                    leafRatio * 100f, ModelConfig.MIN_LEAF_PIXEL_RATIO * 100f));
        }

        CottonBinaryResearchClassifier.Prediction prediction = cottonClassifier.classify(bitmap224);
        long totalLatency = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        if (reqId == requestIdCounter.get()) {
            state.postValue(ResearchDemoState.cottonCompleted(bitmap224, prediction, totalLatency));
        }
    }

    private void postFailure(long reqId, @Nullable Bitmap bmp, @NonNull String error) {
        isExecuting.set(false);
        if (reqId == requestIdCounter.get()) {
            state.postValue(ResearchDemoState.error(bmp, error, currentModeModelsAvailable()));
        }
    }

    @NonNull
    private String getStringRes(int resId, @NonNull String fallback) {
        try {
            String s = appContext.getString(resId);
            return (s != null) ? s : fallback;
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    @Override
    protected void onCleared() {
        if (domainExecutor != null) {
            domainExecutor.close();
            domainExecutor = null;
        }
        if (diseaseExecutor != null) {
            diseaseExecutor.close();
            diseaseExecutor = null;
        }
        if (cottonClassifier != null) {
            try {
                cottonClassifier.close();
            } catch (Throwable ignored) {
                // Native interpreter cleanup should not crash screen teardown.
            }
            cottonClassifier = null;
        }
    }

    private static void logDebug(String tag, String msg) {
        try { Log.d(tag, msg); } catch (Throwable ignore) { /* Unmocked */ }
    }

    private static void logError(String tag, String msg, Throwable t) {
        try { Log.e(tag, msg, t); } catch (Throwable ignore) { /* Unmocked */ }
    }

    @NonNull
    public static ViewModelProvider.Factory provideFactory(
            @NonNull Context context, @NonNull AppExecutors executors) {
        if (!com.cropdoc.app.BuildConfig.DEBUG) {
            throw new IllegalStateException("ResearchDemoViewModel factory prohibited in release.");
        }
        return new ViewModelProvider.Factory() {
            @NonNull @Override @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(ResearchDemoViewModel.class)) {
                    return (T) new ResearchDemoViewModel(context, executors);
                }
                throw new IllegalArgumentException("Unknown class: " + modelClass.getName());
            }
        };
    }
}
