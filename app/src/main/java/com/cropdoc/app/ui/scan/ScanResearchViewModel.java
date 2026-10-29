package com.cropdoc.app.ui.scan;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ui.history.ResearchScanHistoryStore;
import com.cropdoc.app.ml.LeafPreprocessor;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.ml.policy.CropDomain;
import com.cropdoc.app.ml.policy.InferenceDecisionPolicy;
import com.cropdoc.app.ml.policy.InferencePolicyConfig;
import com.cropdoc.app.ml.research.CottonBinaryResearchClassifier;
import com.cropdoc.app.ml.research.LiteRtResearchModelExecutor;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import com.cropdoc.app.ml.research.ResearchInferenceHarness;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.tensorflow.lite.DataType;

/**
 * Crop-selected model-bundle inference for the existing camera/gallery Scan screen.
 *
 * It deliberately bypasses AnalyzeLeafUseCase so research outputs are never written as verified
 * Room diagnoses or used to retrieve treatment advice. ResearchScanHistoryStore keeps a local copy
 * and uploads metadata-only records to users/{uid}/research_scans for signed-in non-anonymous users.
 * Every model asset is verified by SHA-256 and tensor-contract checks.
 */
public final class ScanResearchViewModel extends ViewModel {

    private static final String TAG = "ScanResearch";
    private static final String DOMAIN_ASSET = "research_models/crop_domain_gate_fp32.tflite";
    private static final String DISEASE_ASSET = "research_models/unified_six_class_fp32.tflite";

    private static final String DOMAIN_SHA256 =
            "a8c39823dbb1cbe5b81acccc65412c4a230283ee22539aec359602215be2fe3d";
    private static final String DISEASE_SHA256 =
            "1d3b8b2773c3b0580d77e54419a99f594c0ef21f04a8b098f22f6f756938bb96";

    @NonNull private final Context appContext;
    @NonNull private final AppExecutors executors;
    @NonNull private final ResearchScanHistoryStore researchHistoryStore;
    @NonNull private final MutableLiveData<ScanResearchState> state =
            new MutableLiveData<>(ScanResearchState.idle());
    @NonNull private final AtomicBoolean analyzing = new AtomicBoolean(false);
    @NonNull private final AtomicLong requestIds = new AtomicLong(0L);

    @Nullable private LiteRtResearchModelExecutor domainExecutor;
    @Nullable private LiteRtResearchModelExecutor diseaseExecutor;
    @Nullable private ResearchInferenceHarness riceGroundnutHarness;
    @Nullable private CottonBinaryResearchClassifier cottonClassifier;
    @Nullable private String riceGroundnutLoadError;
    @Nullable private String cottonLoadError;
    private boolean riceGroundnutModelsInitialized;

    public ScanResearchViewModel(@NonNull Context context, @NonNull AppExecutors executors) {
        this.appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        this.executors = Objects.requireNonNull(executors, "executors");
        this.researchHistoryStore = new ResearchScanHistoryStore(this.appContext);
        this.executors.diskIo().execute(this.researchHistoryStore::retryPendingUploadsForCurrentUser);
    }

    @NonNull
    public LiveData<ScanResearchState> getState() {
        return state;
    }

    public boolean isAnalyzing() {
        return analyzing.get();
    }

    /** Clears a terminal result after the Fragment has displayed it once. */
    public void clearResult() {
        state.setValue(ScanResearchState.idle());
    }

    public void analyzeImageFile(
            @NonNull File imageFile, @NonNull String cropKey,
            int previewW, int previewH, int reticleW, int reticleH) {
        long requestId = begin(cropKey);
        if (requestId < 0) {
            return;
        }
        executors.mlInference().execute(() -> processFile(
                imageFile, cropKey, previewW, previewH, reticleW, reticleH, requestId));
    }

    public void analyzeGalleryUri(
            @NonNull ContentResolver resolver, @NonNull Uri uri,
            @NonNull File cacheDir, @NonNull String cropKey) {
        long requestId = begin(cropKey);
        if (requestId < 0) {
            return;
        }
        executors.diskIo().execute(() -> {
            File temp = new File(cacheDir, "picked_leaf_research_" + requestId + ".jpg");
            try (InputStream input = resolver.openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) {
                    deleteQuietly(temp);
                    finish(requestId, ScanResearchState.error(cropKey, "The selected image could not be opened."));
                    return;
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
            } catch (Throwable t) {
                deleteQuietly(temp);
                finish(requestId, ScanResearchState.error(
                        cropKey, "Could not copy the selected image: " + safeMessage(t)));
                return;
            }
            executors.mlInference().execute(() -> processFile(temp, cropKey, 0, 0, 0, 0, requestId));
        });
    }

    private long begin(@NonNull String cropKey) {
        if (!analyzing.compareAndSet(false, true)) {
            return -1L;
        }
        long id = requestIds.incrementAndGet();
        state.setValue(ScanResearchState.loading(cropKey));
        return id;
    }

    private synchronized void initializeRiceGroundnutModels() {
        if (riceGroundnutModelsInitialized) {
            return;
        }
        riceGroundnutModelsInitialized = true;
        try {
            verifyResearchAsset(DOMAIN_ASSET, DOMAIN_SHA256);
            verifyResearchAsset(DISEASE_ASSET, DISEASE_SHA256);

            domainExecutor = LiteRtResearchModelExecutor.fromAsset(
                    appContext, DOMAIN_ASSET, 3, null);
            if (!domainExecutor.isAvailable()) {
                throw new IOException("Crop-domain model could not load: "
                        + domainExecutor.getLoadErrorMessage());
            }
            diseaseExecutor = LiteRtResearchModelExecutor.fromAsset(
                    appContext, DISEASE_ASSET, 6, null);
            if (!diseaseExecutor.isAvailable()) {
                throw new IOException("Six-class model could not load: "
                        + diseaseExecutor.getLoadErrorMessage());
            }

            validateTensorContract(domainExecutor,
                    new int[] {1, 3, 224, 224}, new int[] {1, 3}, DOMAIN_ASSET);
            validateTensorContract(diseaseExecutor,
                    new int[] {1, 3, 224, 224}, new int[] {1, 6}, DISEASE_ASSET);

            riceGroundnutHarness = new ResearchInferenceHarness(
                    new ImageQualityGate(),
                    new InferenceDecisionPolicy(InferencePolicyConfig.createWithCropDomainGate()),
                    domainExecutor,
                    diseaseExecutor);
            riceGroundnutLoadError = null;
        } catch (Throwable t) {
            if (domainExecutor != null) {
                domainExecutor.close();
                domainExecutor = null;
            }
            if (diseaseExecutor != null) {
                diseaseExecutor.close();
                diseaseExecutor = null;
            }
            riceGroundnutHarness = null;
            riceGroundnutLoadError = "Rice/Groundnut model bundle unavailable: " + safeMessage(t);
            Log.e(TAG, riceGroundnutLoadError, t);
        }
    }

    private void verifyResearchAsset(@NonNull String assetName, @NonNull String expectedSha) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 digest support is unavailable.", e);
        }
        long totalBytes = 0L;
        byte[] firstEight = new byte[8];
        int headerCount = 0;
        try (InputStream input = appContext.getAssets().open(assetName)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (headerCount < firstEight.length) {
                    int copy = Math.min(count, firstEight.length - headerCount);
                    System.arraycopy(buffer, 0, firstEight, headerCount, copy);
                    headerCount += copy;
                }
                digest.update(buffer, 0, count);
                totalBytes += count;
            }
        } catch (IOException e) {
            throw new IOException("Missing model-bundle asset " + assetName + ". Rebuild the application with the Scan model bundle assets.", e);
        }
        if (totalBytes < 512L * 1024L) {
            throw new IOException(assetName + " is too small to be a valid model-bundle component (" + totalBytes + " bytes).");
        }
        if (headerCount < 8 || firstEight[4] != 'T' || firstEight[5] != 'F'
                || firstEight[6] != 'L' || firstEight[7] != '3') {
            throw new IOException(assetName + " does not have a valid TFLite TFL3 header.");
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        if (!expectedSha.equalsIgnoreCase(actual.toString())) {
            throw new IOException("SHA-256 mismatch for " + assetName
                    + ". Expected " + expectedSha + " but found " + actual + ".");
        }
    }

    private static void validateTensorContract(
            @NonNull LiteRtResearchModelExecutor executor,
            @NonNull int[] expectedInput, @NonNull int[] expectedOutput,
            @NonNull String assetName) throws IOException {
        if (!java.util.Arrays.equals(executor.getInputTensorShape(), expectedInput)
                || executor.getInputTensorDataType() != DataType.FLOAT32
                || !java.util.Arrays.equals(executor.getOutputTensorShape(), expectedOutput)
                || executor.getOutputTensorDataType() != DataType.FLOAT32) {
            throw new IOException("Tensor contract mismatch for " + assetName
                    + "; expected float32 " + java.util.Arrays.toString(expectedInput)
                    + " -> " + java.util.Arrays.toString(expectedOutput) + ".");
        }
    }

    private void processFile(
            @NonNull File rawFile, @NonNull String cropKey,
            int previewW, int previewH, int reticleW, int reticleH, long requestId) {
        Bitmap decoded = null;
        Bitmap crop = null;
        try {
            decoded = LeafPreprocessor.decodeSampledBitmap(rawFile);
            if (decoded == null) {
                finish(requestId, ScanResearchState.error(cropKey, "Image could not be decoded. Choose another clear photo."));
                return;
            }
            crop = LeafPreprocessor.extractReticleBitmap(
                    decoded, previewW, previewH, reticleW, reticleH);
            if (crop == null || crop.isRecycled()) {
                finish(requestId, ScanResearchState.error(cropKey, "Could not extract the leaf from the scan frame."));
                return;
            }
            ScanResearchState result = runSelectedModel(crop, cropKey);
            // Persist this experimental attempt in separate Research History. It is never written
            // to verified Room scan records or used to retrieve advice. The store keeps the entry
            // locally and uploads metadata-only to Firestore for the currently signed-in account.
            if (result.getStatus() == ScanResearchState.Status.RESULT
                    || result.getStatus() == ScanResearchState.Status.REJECTED
                    || result.getStatus() == ScanResearchState.Status.UNAVAILABLE) {
                try {
                    researchHistoryStore.saveAttempt(crop, result);
                } catch (Throwable historyError) {
                    Log.w(TAG, "Could not save research scan history entry", historyError);
                }
            }
            finish(requestId, result);
        } catch (Throwable t) {
            finish(requestId, ScanResearchState.error(
                    cropKey, "Model inference failed safely: " + safeMessage(t)));
        } finally {
            deleteQuietly(rawFile);
            if (crop != null && crop != decoded && !crop.isRecycled()) {
                crop.recycle();
            }
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    @NonNull
    private ScanResearchState runSelectedModel(@NonNull Bitmap crop, @NonNull String cropKey) {
        if ("cotton".equals(cropKey)) {
            return runCottonPilot(crop);
        }
        if (!"rice".equals(cropKey) && !"groundnut".equals(cropKey)) {
            return ScanResearchState.error(cropKey, "Unknown crop selection. No inference was run.");
        }
        initializeRiceGroundnutModels();
        if (riceGroundnutHarness == null) {
            return ScanResearchState.unavailable(
                    cropKey, riceGroundnutLoadError != null
                            ? riceGroundnutLoadError
                            : "The Rice/Groundnut model bundle is unavailable.");
        }

        long startNs = System.nanoTime();
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE)
                .order(ByteOrder.nativeOrder());
        ResearchHarnessOutcome outcome = riceGroundnutHarness.processImage(crop, byteBuffer);
        long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);

        if (!outcome.isAccepted()) {
            return ScanResearchState.rejected(cropKey,
                    "No result was accepted by the model safety checks. " + outcome.getExplanation());
        }

        CropDomain expectedDomain = "rice".equals(cropKey)
                ? CropDomain.SUPPORTED_RICE : CropDomain.SUPPORTED_GROUNDNUT;
        if (outcome.getDetectedDomain() != expectedDomain) {
            return ScanResearchState.rejected(cropKey,
                    "The crop-domain model did not identify the selected crop. Choose the detected crop or retake the photo.");
        }

        String classId = outcome.getPredictedClassId();
        if (classId == null || !classId.startsWith(cropKey + "_")) {
            return ScanResearchState.rejected(cropKey,
                    "The disease model output did not match the selected crop. No prediction was shown.");
        }
        return ScanResearchState.result(cropKey, classId, outcome.getDiseaseConfidence(), latencyMs,
                "The model accepted this image; the output is preliminary and not a field-validated diagnosis.");
    }

    @NonNull
    private synchronized ScanResearchState runCottonPilot(@NonNull Bitmap crop) {
        // A lightweight image-quality and foliar-coverage check runs before the cotton binary pilot.
        // The existing domain gate does not support cotton, so selected-crop context is not treated
        // as automatic botanical identification.
        ByteBuffer leafBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE)
                .order(ByteOrder.nativeOrder());
        int[] pixels = new int[ModelConfig.PIXEL_COUNT];
        float leafRatio = LeafPreprocessor.populateByteBufferAndComputeLeafRatio(crop, leafBuffer, pixels);
        ImageQualityGate.QualityResult quality = new ImageQualityGate().evaluate(crop, leafBuffer);
        if (!quality.isAccepted()) {
            return ScanResearchState.rejected("cotton",
                    "Image quality check rejected this input. " + quality.getExplanation());
        }
        if (leafRatio < ModelConfig.MIN_LEAF_PIXEL_RATIO) {
            return ScanResearchState.rejected("cotton",
                    "The image did not pass the foliage-coverage check. Frame one clear cotton leaf.");
        }

        if (cottonClassifier == null) {
            try {
                cottonClassifier = CottonBinaryResearchClassifier.create(appContext.getAssets());
                cottonLoadError = null;
            } catch (Throwable t) {
                cottonLoadError = "Cotton pilot model is not installed or did not pass its integrity checks. "
                        + "Export cotton_binary_fp32.tflite and its matching manifest and copy both into "
                        + "app/src/main/assets/research_models/. Details: " + safeMessage(t);
                Log.w(TAG, cottonLoadError, t);
                return ScanResearchState.unavailable("cotton", cottonLoadError);
            }
        }

        try {
            CottonBinaryResearchClassifier.Prediction prediction = cottonClassifier.classify(crop);
            return ScanResearchState.result("cotton", prediction.getClassId(), prediction.getModelScore(),
                    prediction.getInferenceTimeMs(),
                    "Cotton model score is uncalibrated; the source labels have not received plant-pathologist validation. "
                            + "No diagnosis was saved or used to retrieve treatment advice.");
        } catch (Throwable t) {
            cottonLoadError = "Cotton pilot inference failed: " + safeMessage(t);
            return ScanResearchState.unavailable("cotton", cottonLoadError);
        }
    }

    private void finish(long requestId, @NonNull ScanResearchState result) {
        if (requestId == requestIds.get()) {
            analyzing.set(false);
            state.postValue(result);
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Throwable ignored) {
                // Cache cleanup failure does not change model output.
            }
        }
    }

    @NonNull
    private static String safeMessage(@Nullable Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
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
            cottonClassifier.close();
            cottonClassifier = null;
        }
    }

    @NonNull
    public static ViewModelProvider.Factory provideFactory(
            @NonNull Context context, @NonNull AppExecutors executors) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (modelClass.isAssignableFrom(ScanResearchViewModel.class)) {
                    return (T) new ScanResearchViewModel(context, executors);
                }
                throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
            }
        };
    }
}
