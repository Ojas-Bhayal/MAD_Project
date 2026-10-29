package com.cropdoc.app.ml.research;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/** Package-private integrity checks for the cotton research model and sidecar manifest. */
final class CottonResearchModelLoader {
    static final String MODEL_ASSET = "research_models/cotton_binary_fp32.tflite";
    static final String MANIFEST_ASSET = "research_models/cotton_binary_fp32_manifest.json";
    private static final long MIN_MODEL_BYTES = 512L * 1024L;
    private static final String[] CLASSES = {"cotton_bacterial_blight", "cotton_healthy"};

    private CottonResearchModelLoader() { }

    @NonNull
    static Interpreter loadValidated(@NonNull AssetManager assets) throws IOException {
        JSONObject manifest = readManifest(assets);
        validateManifest(manifest);
        String expectedSha = manifest.optString("sha256", "").toLowerCase(Locale.ROOT);
        if (!expectedSha.matches("[0-9a-f]{64}")) {
            throw new IOException("Cotton research manifest has no valid SHA-256 digest.");
        }

        ByteBuffer mapped;
        try (AssetFileDescriptor afd = assets.openFd(MODEL_ASSET);
             FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            long length = afd.getLength();
            if (length < MIN_MODEL_BYTES) {
                throw new IOException("Cotton research TFLite asset is missing or below the size threshold.");
            }
            mapped = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), length);
        } catch (IOException e) {
            throw new IOException("Unable to open cotton research asset: " + e.getMessage(), e);
        }
        mapped.order(ByteOrder.nativeOrder());
        verifyHeader(mapped);
        if (!expectedSha.equals(sha256Asset(assets, MODEL_ASSET))) {
            throw new IOException("Cotton research model SHA-256 mismatch. Copy the model and manifest together.");
        }

        Interpreter runner = new Interpreter(mapped, new Interpreter.Options().setNumThreads(4));
        try {
            verifyTensors(runner);
            return runner;
        } catch (IOException | RuntimeException e) {
            runner.close();
            throw e;
        }
    }

    private static JSONObject readManifest(AssetManager assets) throws IOException {
        try (InputStream in = assets.open(MANIFEST_ASSET); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Cotton research manifest is invalid JSON.", e);
        }
    }

    private static void validateManifest(JSONObject manifest) throws IOException {
        if (!"cotton_binary_fp32".equals(manifest.optString("model_id"))
                || !"0.1.0-research".equals(manifest.optString("model_version"))
                || !manifest.optBoolean("research_only", false)
                || !"BLOCKED".equals(manifest.optString("production_approval"))
                || !"cc1fbcebaa3a80659da42905bcbdf548df4fd81815701de82ff4a9006077b33c"
                        .equalsIgnoreCase(manifest.optString("checkpoint_sha256", ""))) {
            throw new IOException("Cotton model manifest does not declare the expected checkpoint and research-only status.");
        }
        if (!"float32".equals(manifest.optString("input_dtype"))
                || !"float32".equals(manifest.optString("output_dtype"))) {
            throw new IOException("Cotton model tensor data types in manifest are incompatible.");
        }
        if (!"NCHW".equals(manifest.optString("input_layout"))) {
            throw new IOException("Cotton model must declare NCHW input layout.");
        }
        if (!"UNVALIDATED_PILOT (UNREVIEWED_SOURCE_LABELS)".equals(
                manifest.optString("diagnostic_status"))) {
            throw new IOException("Cotton model manifest must retain its unvalidated-pilot status.");
        }
        JSONObject normalization = manifest.optJSONObject("normalization");
        if (normalization == null
                || !floatArrayEquals(normalization.optJSONArray("mean"), new float[] {0.485f, 0.456f, 0.406f})
                || !floatArrayEquals(normalization.optJSONArray("std"), new float[] {0.229f, 0.224f, 0.225f})) {
            throw new IOException("Cotton model ImageNet normalization is missing or incompatible.");
        }
        if (!arrayEquals(manifest.optJSONArray("input_shape"), new int[] {1, 3, 224, 224})
                || !arrayEquals(manifest.optJSONArray("output_shape"), new int[] {1, 2})) {
            throw new IOException("Cotton model tensor shapes in manifest are incompatible.");
        }
        JSONArray classOrder = manifest.optJSONArray("class_order");
        if (classOrder == null || classOrder.length() != CLASSES.length
                || !CLASSES[0].equals(classOrder.optString(0))
                || !CLASSES[1].equals(classOrder.optString(1))) {
            throw new IOException("Cotton model class order in manifest is incompatible.");
        }
    }


    private static boolean floatArrayEquals(@Nullable JSONArray actual, @NonNull float[] expected) {
        if (actual == null || actual.length() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            double value = actual.optDouble(i, Double.NaN);
            if (!Double.isFinite(value) || Math.abs(value - expected[i]) > 1.0e-6) {
                return false;
            }
        }
        return true;
    }

    private static boolean arrayEquals(@Nullable JSONArray actual, @NonNull int[] expected) {
        if (actual == null || actual.length() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual.optInt(i, Integer.MIN_VALUE) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static void verifyHeader(@NonNull ByteBuffer model) throws IOException {
        if (model.remaining() < 8) {
            throw new IOException("Cotton research model is truncated.");
        }
        int start = model.position();
        model.position(start + 4);
        byte[] magic = new byte[4];
        model.get(magic);
        model.position(start);
        if (!Arrays.equals(magic, new byte[] {'T', 'F', 'L', '3'})) {
            throw new IOException("Cotton research model has an invalid TFLite header.");
        }
    }

    private static void verifyTensors(@NonNull Interpreter runner) throws IOException {
        Tensor input = runner.getInputTensor(0);
        Tensor output = runner.getOutputTensor(0);
        if (!Arrays.equals(input.shape(), new int[] {1, 3, 224, 224}) || input.dataType() != DataType.FLOAT32) {
            throw new IOException("Expected cotton input float32 [1,3,224,224].");
        }
        if (!Arrays.equals(output.shape(), new int[] {1, 2}) || output.dataType() != DataType.FLOAT32) {
            throw new IOException("Expected cotton output float32 [1,2].");
        }
    }

    @NonNull
    private static String sha256Asset(@NonNull AssetManager assets, @NonNull String path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        try (InputStream input = assets.open(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }
}
