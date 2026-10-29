package com.cropdoc.app.data.remote;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ml.ModelConfig;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Spark-tier compatible OTA model and label pair manager using Firebase Remote Config for
 * SHA-256 hash verification and API 24-safe atomic file replacement.
 */
public class ModelUpdateManager {

    public static final String KEY_LATEST_VERSION = "latest_model_version";
    public static final String KEY_EXPECTED_SHA256 = "latest_model_sha256";
    public static final String KEY_EXPECTED_LABELS_SHA256 = "latest_labels_sha256";
    private static final String OTA_MODEL_FILENAME = "cropdoc_ota_verified.tflite";
    private static final String OTA_LABELS_FILENAME = "cropdoc_ota_labels.txt";
    private static final long TIMEOUT_SECONDS = 15L;

    @NonNull
    private final Context appContext;

    @NonNull
    private volatile String activeVersion = ModelConfig.DEFAULT_MODEL_VERSION;

    public ModelUpdateManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    public String getActiveModelVersion() {
        return activeVersion;
    }

    @Nullable
    public File getVerifiedOtaModelFile() {
        File file = new File(appContext.getFilesDir(), OTA_MODEL_FILENAME);
        return (file.exists() && file.length() > 0) ? file : null;
    }

    @Nullable
    public File getVerifiedOtaLabelsFile() {
        File file = new File(appContext.getFilesDir(), OTA_LABELS_FILENAME);
        return (file.exists() && file.length() > 0) ? file : null;
    }

    public boolean verifyAndInstallStagedModel(
            @NonNull File stagedFile,
            @NonNull String expectedSha256,
            @NonNull String targetVersion) {
        if (!stagedFile.exists() || expectedSha256.trim().isEmpty()) {
            cleanupStagedFile(stagedFile);
            return false;
        }
        try {
            String actualHash = computeSha256Hex(stagedFile);
            if (!expectedSha256.trim().equalsIgnoreCase(actualHash)) {
                return false;
            }
            File destFile = new File(appContext.getFilesDir(), OTA_MODEL_FILENAME);
            boolean installed = replaceFileAtomically(stagedFile, destFile);
            if (installed) {
                activeVersion = targetVersion;
            }
            return installed;
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        } finally {
            cleanupStagedFile(stagedFile);
        }
    }

    public boolean verifyAndInstallStagedModelPair(
            @NonNull File stagedModelFile,
            @NonNull String expectedModelSha256,
            @NonNull File stagedLabelsFile,
            @NonNull String expectedLabelsSha256,
            @NonNull String targetVersion) {
        if (!stagedModelFile.exists() || !stagedLabelsFile.exists()
                || expectedModelSha256.trim().isEmpty()
                || expectedLabelsSha256.trim().isEmpty()) {
            cleanupStagedFile(stagedModelFile);
            cleanupStagedFile(stagedLabelsFile);
            return false;
        }
        try {
            String actualModelHash = computeSha256Hex(stagedModelFile);
            String actualLabelsHash = computeSha256Hex(stagedLabelsFile);
            if (!expectedModelSha256.trim().equalsIgnoreCase(actualModelHash)
                    || !expectedLabelsSha256.trim().equalsIgnoreCase(actualLabelsHash)) {
                return false;
            }
            File destLabels = new File(appContext.getFilesDir(), OTA_LABELS_FILENAME);
            File destModel = new File(appContext.getFilesDir(), OTA_MODEL_FILENAME);
            if (!replaceFileAtomically(stagedLabelsFile, destLabels)) {
                return false;
            }
            if (!replaceFileAtomically(stagedModelFile, destModel)) {
                //noinspection ResultOfMethodCallIgnored
                destLabels.delete();
                return false;
            }
            activeVersion = targetVersion;
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        } finally {
            cleanupStagedFile(stagedModelFile);
            cleanupStagedFile(stagedLabelsFile);
        }
    }

    public static boolean replaceFileAtomically(@NonNull File stagedFile, @NonNull File destFile) {
        try {
            if (stagedFile.renameTo(destFile)) {
                return true;
            }
            try (FileInputStream in = new FileInputStream(stagedFile);
                 FileOutputStream out = new FileOutputStream(destFile, false);
                 FileChannel inCh = in.getChannel();
                 FileChannel outCh = out.getChannel()) {
                inCh.transferTo(0, inCh.size(), outCh);
                out.getFD().sync();
            }
            return destFile.exists() && destFile.length() > 0;
        } catch (IOException e) {
            return false;
        } finally {
            cleanupStagedFile(stagedFile);
        }
    }

    public void clearInvalidOtaFiles() {
        File model = new File(appContext.getFilesDir(), OTA_MODEL_FILENAME);
        File labels = new File(appContext.getFilesDir(), OTA_LABELS_FILENAME);
        cleanupStagedFile(model);
        cleanupStagedFile(labels);
        activeVersion = ModelConfig.DEFAULT_MODEL_VERSION;
    }

    private static void cleanupStagedFile(@NonNull File file) {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public boolean refreshRemoteConfigThresholds() {
        try {
            FirebaseApp.getInstance();
            FirebaseRemoteConfig rc = FirebaseRemoteConfig.getInstance();
            Tasks.await(rc.fetchAndActivate(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String remoteVer = rc.getString(KEY_LATEST_VERSION);
            if (!remoteVer.isEmpty()) {
                activeVersion = remoteVer;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @NonNull
    public static String computeSha256Hex(
            @NonNull File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder hex = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }
}
