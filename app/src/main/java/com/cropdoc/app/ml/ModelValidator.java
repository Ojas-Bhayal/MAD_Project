package com.cropdoc.app.ml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/**
 * Validates ML model file integrity, cryptographic SHA-256 manifests, flatbuffer headers,
 * tensor shapes/types, and label checksums (G3).
 *
 * Streams hashes in 8 KB chunks off the main thread and caches results in a thread-safe map.
 */
public final class ModelValidator {

    public static final long MIN_REAL_MODEL_BYTES = 512L * 1024L; // 512 KB
    public static final int EXPECTED_INPUT_WIDTH = 224;
    public static final int EXPECTED_INPUT_HEIGHT = 224;
    public static final int EXPECTED_INPUT_CHANNELS = 3;
    private static final int HASH_BUFFER_SIZE = 8192;

    // Process-wide verification cache keyed by (modelIdentifier + version)
    private static final Map<String, ValidationResult> VALIDATION_CACHE = new ConcurrentHashMap<>();

    public static final class ValidationResult {
        private final boolean valid;
        @Nullable private final ClassificationOutcome.UnavailableReason reason;
        @NonNull private final String message;
        @Nullable private final ModelManifest manifest;

        private ValidationResult(
                boolean valid,
                @Nullable ClassificationOutcome.UnavailableReason reason,
                @NonNull String message,
                @Nullable ModelManifest manifest) {
            this.valid = valid;
            this.reason = reason;
            this.message = Objects.requireNonNull(message, "message");
            this.manifest = manifest;
        }

        @NonNull
        public static ValidationResult success(@NonNull ModelManifest manifest) {
            return new ValidationResult(true, null, "Model and manifest validated successfully", manifest);
        }

        @NonNull
        public static ValidationResult failure(
                @NonNull ClassificationOutcome.UnavailableReason reason,
                @NonNull String message) {
            return new ValidationResult(false, reason, message, null);
        }

        public boolean isValid() {
            return valid;
        }

        @Nullable
        public ClassificationOutcome.UnavailableReason getReason() {
            return reason;
        }

        @NonNull
        public String getMessage() {
            return message;
        }

        @Nullable
        public ModelManifest getManifest() {
            return manifest;
        }

        @NonNull
        @Override
        public String toString() {
            return valid
                    ? "ValidationResult{VALID, model=" + (manifest != null ? manifest.getModelId() : "unknown") + "}"
                    : "ValidationResult{INVALID, reason=" + reason + ", msg='" + message + "'}";
        }
    }

    private ModelValidator() {
        // Utility class
    }

    public static void clearCache() {
        VALIDATION_CACHE.clear();
    }

    /**
     * Validates model file integrity, flatbuffer headers, tensor shapes, and manifest (G3).
     */
    @NonNull
    public static ValidationResult validateModelWithManifest(
            long fileLength,
            @NonNull ByteBuffer modelBuffer,
            @Nullable InputStream modelStream,
            @Nullable Interpreter interpreter,
            @NonNull List<String> labels,
            @NonNull String modelVersion,
            @Nullable String manifestJson,
            @NonNull String cacheKey) {

        ValidationResult cached = VALIDATION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        ValidationResult result = performValidation(
                fileLength, modelBuffer, modelStream, interpreter, labels, modelVersion, manifestJson);
        VALIDATION_CACHE.put(cacheKey, result);
        return result;
    }

    @NonNull
    private static ValidationResult performValidation(
            long fileLength,
            @NonNull ByteBuffer modelBuffer,
            @Nullable InputStream modelStream,
            @Nullable Interpreter interpreter,
            @NonNull List<String> labels,
            @NonNull String modelVersion,
            @Nullable String manifestJson) {

        if (manifestJson == null || manifestJson.trim().isEmpty()) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MANIFEST_MISSING,
                    "Model manifest JSON is missing or empty.");
        }
        ModelManifest manifest;
        try {
            manifest = ModelManifest.parse(manifestJson);
        } catch (Exception e) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MANIFEST_CORRUPT,
                    "Failed to parse model manifest: " + e.getMessage());
        }
        if (!modelVersion.equals(manifest.getModelVersion())) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MANIFEST_CORRUPT,
                    "Manifest version (" + manifest.getModelVersion()
                            + ") does not match requested model version (" + modelVersion + ").");
        }

        ValidationResult headerRes = verifyModelHeaderAndSize(fileLength, modelBuffer);
        if (headerRes != null) { return headerRes; }

        ValidationResult shaRes = verifySha256Digest(modelStream, modelBuffer, manifest.getSha256());
        if (shaRes != null) { return shaRes; }

        ValidationResult labelsRes = verifyLabelsAndChecksum(labels, manifest);
        if (labelsRes != null) { return labelsRes; }

        if (interpreter != null) {
            ValidationResult tensorValidation = verifyInterpreterTensors(interpreter, manifest);
            if (tensorValidation != null) { return tensorValidation; }
        }

        return ValidationResult.success(manifest);
    }

    @Nullable
    private static ValidationResult verifyModelHeaderAndSize(long fileLength, @NonNull ByteBuffer modelBuffer) {
        if (fileLength < MIN_REAL_MODEL_BYTES) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED,
                    "Model file size (" + fileLength + " bytes) is below minimum threshold ("
                            + MIN_REAL_MODEL_BYTES + " bytes). Placeholder model detected.");
        }
        if (modelBuffer.remaining() < 16) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED,
                    "Model buffer too small for TFLite flatbuffer header.");
        }
        int originalPos = modelBuffer.position();
        byte[] idBytes = new byte[4];
        modelBuffer.position(originalPos + 4);
        modelBuffer.get(idBytes);
        modelBuffer.position(originalPos);
        String identifier = new String(idBytes, StandardCharsets.US_ASCII);
        if (!"TFL3".equals(identifier)) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED,
                    "Invalid TFLite header identifier: '" + identifier + "' (expected 'TFL3')");
        }
        return null;
    }

    @Nullable
    private static ValidationResult verifySha256Digest(
            @Nullable InputStream modelStream,
            @NonNull ByteBuffer modelBuffer,
            @NonNull String expectedSha256) {
        String computedSha256;
        try {
            computedSha256 = modelStream != null ? computeSha256(modelStream) : computeSha256(modelBuffer);
        } catch (Exception e) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED,
                    "Failed to compute model SHA-256: " + e.getMessage());
        }
        if (computedSha256 == null || computedSha256.isEmpty()) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MANIFEST_DIGEST_MISMATCH,
                    "Cannot verify model digest: model stream and buffer are unavailable.");
        }
        if (!computedSha256.equalsIgnoreCase(expectedSha256)) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.MANIFEST_DIGEST_MISMATCH,
                    "Model SHA-256 digest mismatch. Computed: " + computedSha256
                            + ", Manifest: " + expectedSha256);
        }
        return null;
    }

    @Nullable
    private static ValidationResult verifyLabelsAndChecksum(
            @NonNull List<String> labels,
            @NonNull ModelManifest manifest) {
        if (labels.isEmpty()) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.LABELS_MISMATCH,
                    "Labels list is empty.");
        }
        if (labels.size() != manifest.getOutputClassCount()) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.LABELS_MISMATCH,
                    "Labels count (" + labels.size() + ") does not match manifest class count ("
                            + manifest.getOutputClassCount() + ").");
        }
        long computedChecksum = computeLabelOrderChecksum(labels);
        String computedHex = Long.toHexString(computedChecksum).toLowerCase(Locale.US);
        String computedDec = Long.toString(computedChecksum);
        String manifestChecksum = manifest.getLabelsChecksum().toLowerCase(Locale.US);
        if (!manifestChecksum.equals(computedHex) && !manifestChecksum.equals(computedDec)) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.LABEL_ORDER_CHECKSUM_MISMATCH,
                    "Labels checksum mismatch. Computed CRC32: " + computedHex + " (" + computedDec
                            + "), Manifest: " + manifest.getLabelsChecksum());
        }
        return null;
    }

    @Nullable
    private static ValidationResult verifyInterpreterTensors(
            @NonNull Interpreter interpreter,
            @NonNull ModelManifest manifest) {
        if (interpreter.getInputTensorCount() < 1) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Model has no input tensors.");
        }
        Tensor inT = interpreter.getInputTensor(0);
        if (!shapesEqual(inT.shape(), manifest.getInputShape())) {
            return ValidationResult.failure(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Input tensor shape mismatch: expected " + java.util.Arrays.toString(manifest.getInputShape())
                            + ", got " + java.util.Arrays.toString(inT.shape()));
        }
        if (!isDataTypeMatching(inT.dataType(), manifest.getInputDtype())) {
            String actual = inT.dataType() != null ? inT.dataType().name().toLowerCase(Locale.US) : "null";
            return ValidationResult.failure(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Input tensor dtype mismatch: expected " + manifest.getInputDtype() + ", got " + actual);
        }
        if (interpreter.getOutputTensorCount() < 1) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Model has no output tensors.");
        }
        Tensor outT = interpreter.getOutputTensor(0);
        int[] outShape = outT.shape();
        int outClasses = outShape.length >= 2 ? outShape[1] : outShape[0];
        if (outClasses != manifest.getOutputClassCount()) {
            return ValidationResult.failure(ClassificationOutcome.UnavailableReason.LABELS_MISMATCH,
                    "Output tensor class count (" + outClasses
                            + ") does not match manifest count (" + manifest.getOutputClassCount() + ").");
        }
        if (!isDataTypeMatching(outT.dataType(), manifest.getOutputDtype())) {
            String actual = outT.dataType() != null ? outT.dataType().name().toLowerCase(Locale.US) : "null";
            return ValidationResult.failure(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Output tensor dtype mismatch: expected " + manifest.getOutputDtype() + ", got " + actual);
        }
        return null;
    }

    static boolean isDataTypeMatching(@Nullable DataType dt, @NonNull String expected) {
        if (dt == null) {
            return false;
        }
        String name = dt.name().toLowerCase(Locale.US);
        String exp = expected.toLowerCase(Locale.US);
        return name.equals(exp);
    }

    /**
     * Backward-compatible validator method without manifest JSON.
     */
    @NonNull
    public static ValidationResult validateModel(
            long fileLength,
            @NonNull ByteBuffer modelBuffer,
            @Nullable Interpreter interpreter,
            @NonNull List<String> labels,
            @NonNull String modelVersion) {
        ValidationResult headerRes = verifyModelHeaderAndSize(fileLength, modelBuffer);
        if (headerRes != null) {
            return headerRes;
        }
        if (interpreter != null) {
            ValidationResult tensorRes = verifyLegacyTensors(interpreter, labels.size());
            if (tensorRes != null) {
                return tensorRes;
            }
        }
        return ValidationResult.failure(
                ClassificationOutcome.UnavailableReason.MANIFEST_MISSING,
                "Model manifest is required for verified operation.");
    }

    @Nullable
    private static ValidationResult verifyLegacyTensors(@NonNull Interpreter interpreter, int labelCount) {
        if (interpreter.getInputTensorCount() < 1) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Model has no input tensors.");
        }
        Tensor inputTensor = interpreter.getInputTensor(0);
        int[] inputShape = inputTensor.shape();
        if (inputShape.length != 4
                || inputShape[1] != EXPECTED_INPUT_HEIGHT
                || inputShape[2] != EXPECTED_INPUT_WIDTH
                || inputShape[3] != EXPECTED_INPUT_CHANNELS) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Input tensor shape mismatch: expected [1, 224, 224, 3], got "
                            + java.util.Arrays.toString(inputShape));
        }
        if (interpreter.getOutputTensorCount() < 1) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE,
                    "Model has no output tensors.");
        }
        Tensor outputTensor = interpreter.getOutputTensor(0);
        int[] outputShape = outputTensor.shape();
        int outputClasses = outputShape.length >= 2 ? outputShape[1] : outputShape[0];
        if (outputClasses != labelCount) {
            return ValidationResult.failure(
                    ClassificationOutcome.UnavailableReason.LABELS_MISMATCH,
                    "Output tensor class count (" + outputClasses
                            + ") does not match labels count (" + labelCount + ").");
        }
        return null;
    }

    @NonNull
    public static String computeSha256(@NonNull InputStream is) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        int read;
        while ((read = is.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    @NonNull
    public static String computeSha256(@NonNull ByteBuffer buffer) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.rewind();
        if (duplicate.hasArray()) {
            digest.update(duplicate.array(), duplicate.arrayOffset(), duplicate.limit());
        } else {
            byte[] chunk = new byte[HASH_BUFFER_SIZE];
            while (duplicate.hasRemaining()) {
                int toRead = Math.min(duplicate.remaining(), chunk.length);
                duplicate.get(chunk, 0, toRead);
                digest.update(chunk, 0, toRead);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    @NonNull
    public static String computeLabelsSha256(@NonNull List<String> labels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < labels.size(); i++) {
                digest.update(labels.get(i).getBytes(StandardCharsets.UTF_8));
                if (i < labels.size() - 1) {
                    digest.update((byte) '\n');
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static long computeLabelOrderChecksum(@NonNull List<String> labels) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        for (String label : labels) {
            crc.update(label.getBytes(StandardCharsets.UTF_8));
            crc.update((byte) '\n');
        }
        return crc.getValue();
    }

    private static boolean shapesEqual(int[] a, int[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
