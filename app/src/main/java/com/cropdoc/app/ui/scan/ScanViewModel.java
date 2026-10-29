package com.cropdoc.app.ui.scan;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import com.cropdoc.app.ml.LeafPreprocessor;
import com.cropdoc.app.ui.common.Result;
import com.cropdoc.app.util.ExifPrivacySanitizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ViewModel running gallery URI copying, subsampled Bitmap decoding with EXIF orientation,
 * exact square reticle-box cropping before JPEG persistence, and LiteRT inference on background
 * threads with an atomic double-tap concurrency guard (#4).
 */
public final class ScanViewModel extends ViewModel {

    private static final int COPY_BUFFER_BYTES = 8192;

    @NonNull
    private final AnalyzeLeafUseCase analyzeLeafUseCase;

    @NonNull
    private final AppExecutors executors;

    private final MutableLiveData<Result<AnalyzeLeafUseCase.AnalysisOutput>> scanResult =
            new MutableLiveData<>();

    private final AtomicBoolean analyzing = new AtomicBoolean(false);

    public ScanViewModel(
            @NonNull AnalyzeLeafUseCase analyzeLeafUseCase,
            @NonNull AppExecutors executors) {
        this.analyzeLeafUseCase = Objects.requireNonNull(
                analyzeLeafUseCase, "analyzeLeafUseCase");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @NonNull
    public LiveData<Result<AnalyzeLeafUseCase.AnalysisOutput>> getScanResult() {
        return scanResult;
    }

    public boolean isAnalyzing() {
        return analyzing.get();
    }

    public void clearScanResult() {
        analyzing.set(false);
        scanResult.setValue(null);
    }

    public void analyzeGalleryUri(
            @NonNull ContentResolver resolver,
            @NonNull Uri uri,
            @NonNull File cacheDir,
            @NonNull File sanitizedStorageDir,
            @NonNull String languageCode) {
        if (!analyzing.compareAndSet(false, true)) {
            return;
        }
        scanResult.setValue(Result.loading());
        executors.diskIo().execute(() -> {
            File tempFile = new File(
                    cacheDir, "picked_leaf_" + System.currentTimeMillis() + ".jpg");
            try (InputStream is = resolver.openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                if (is == null) {
                    analyzing.set(false);
                    scanResult.postValue(Result.error("ERROR_IMAGE_DECODE"));
                    return;
                }
                byte[] buf = new byte[COPY_BUFFER_BYTES];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
            } catch (Throwable e) {
                analyzing.set(false);
                scanResult.postValue(Result.error("ERROR_IMAGE_DECODE"));
                return;
            }
            runDecodeAndAnalyze(tempFile, sanitizedStorageDir, languageCode, 0, 0, 0, 0);
        });
    }

    public void analyzeImageFile(
            @NonNull File rawImageFile,
            @NonNull File sanitizedStorageDir,
            @NonNull String languageCode) {
        analyzeImageFile(rawImageFile, sanitizedStorageDir, languageCode, 0, 0, 0, 0);
    }

    public void analyzeImageFile(
            @NonNull File rawImageFile,
            @NonNull File sanitizedStorageDir,
            @NonNull String languageCode,
            int previewW,
            int previewH,
            int reticleW,
            int reticleH) {
        if (!analyzing.compareAndSet(false, true)) {
            return;
        }
        scanResult.setValue(Result.loading());
        executors.mlInference().execute(() -> runDecodeAndAnalyze(
                rawImageFile, sanitizedStorageDir, languageCode,
                previewW, previewH, reticleW, reticleH));
    }

    private void runDecodeAndAnalyze(
            @NonNull File rawImageFile,
            @NonNull File sanitizedStorageDir,
            @NonNull String languageCode,
            int previewW,
            int previewH,
            int reticleW,
            int reticleH) {
        Bitmap decoded = null;
        Bitmap reticleBitmap = null;
        try {
            decoded = LeafPreprocessor.decodeSampledBitmap(rawImageFile);
            deleteTempRawFile(rawImageFile);
            if (decoded == null) {
                scanResult.postValue(Result.error("ERROR_IMAGE_DECODE"));
                return;
            }
            reticleBitmap = LeafPreprocessor.extractReticleBitmap(
                    decoded, previewW, previewH, reticleW, reticleH);
            File cleanFile = ExifPrivacySanitizer.saveSanitizedLeafJpeg(
                    reticleBitmap,
                    sanitizedStorageDir,
                    "leaf_" + System.currentTimeMillis() + ".jpg");
            AnalyzeLeafUseCase.AnalysisOutput output = analyzeLeafUseCase.execute(
                    reticleBitmap, cleanFile.getAbsolutePath(), languageCode);
            scanResult.postValue(Result.success(output));
        } catch (Throwable e) {
            scanResult.postValue(Result.error("ERROR_IMAGE_SAVE"));
        } finally {
            if (reticleBitmap != null && reticleBitmap != decoded && !reticleBitmap.isRecycled()) {
                reticleBitmap.recycle();
            }
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
            analyzing.set(false);
        }
    }

    private static void deleteTempRawFile(@NonNull File rawFile) {
        String name = rawFile.getName();
        if (rawFile.exists() && (name.startsWith("raw_") || name.startsWith("picked_leaf_"))) {
            //noinspection ResultOfMethodCallIgnored
            rawFile.delete();
        }
    }
}
