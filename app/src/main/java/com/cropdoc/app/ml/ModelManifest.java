package com.cropdoc.app.ml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable domain model representing an authenticated Model Manifest.
 * Defines cryptographic digests, expected tensor shapes, label order checksum,
 * preprocessing contract version, and non-diagnostic taxonomy classes (G1, G3).
 */
public final class ModelManifest {

    @NonNull private final String modelId;
    @NonNull private final String modelVersion;
    @NonNull private final String schemaVersion;
    @NonNull private final String sha256;
    @NonNull private final int[] inputShape;
    @NonNull private final String inputDtype;
    private final int outputClassCount;
    @NonNull private final String outputDtype;
    @NonNull private final String preprocessingContractId;
    @NonNull private final String labelsChecksum;
    @NonNull private final Set<String> nonDiagnosticClasses;
    @Nullable private final String evidenceReportHash;

    public ModelManifest(
            @NonNull String modelId,
            @NonNull String modelVersion,
            @NonNull String schemaVersion,
            @NonNull String sha256,
            @NonNull int[] inputShape,
            @NonNull String inputDtype,
            int outputClassCount,
            @NonNull String outputDtype,
            @NonNull String preprocessingContractId,
            @NonNull String labelsChecksum,
            @NonNull Set<String> nonDiagnosticClasses,
            @Nullable String evidenceReportHash) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.US);
        this.inputShape = Objects.requireNonNull(inputShape, "inputShape").clone();
        this.inputDtype = Objects.requireNonNull(inputDtype, "inputDtype");
        this.outputClassCount = outputClassCount;
        this.outputDtype = Objects.requireNonNull(outputDtype, "outputDtype");
        this.preprocessingContractId = Objects.requireNonNull(preprocessingContractId, "preprocessingContractId");
        this.labelsChecksum = Objects.requireNonNull(labelsChecksum, "labelsChecksum").toLowerCase(Locale.US);
        this.nonDiagnosticClasses = Collections.unmodifiableSet(
                new HashSet<>(Objects.requireNonNull(nonDiagnosticClasses, "nonDiagnosticClasses")));
        this.evidenceReportHash = evidenceReportHash != null ? evidenceReportHash.toLowerCase(Locale.US) : null;
    }

    @NonNull public String getModelId() { return modelId; }
    @NonNull public String getModelVersion() { return modelVersion; }
    @NonNull public String getSchemaVersion() { return schemaVersion; }
    @NonNull public String getSha256() { return sha256; }
    @NonNull public int[] getInputShape() { return inputShape.clone(); }
    @NonNull public String getInputDtype() { return inputDtype; }
    public int getOutputClassCount() { return outputClassCount; }
    @NonNull public String getOutputDtype() { return outputDtype; }
    @NonNull public String getPreprocessingContractId() { return preprocessingContractId; }
    @NonNull public String getLabelsChecksum() { return labelsChecksum; }
    @NonNull public Set<String> getNonDiagnosticClasses() { return nonDiagnosticClasses; }
    @Nullable public String getEvidenceReportHash() { return evidenceReportHash; }

    /**
     * Pure Java zero-dependency JSON parser for ModelManifest.
     * Guaranteed to work identically across Android runtime and JVM unit test suites.
     */
    @NonNull
    public static ModelManifest parse(@NonNull String json) throws IllegalArgumentException {
        Objects.requireNonNull(json, "json");
        try {
            String modelId = extractString(json, "model_id");
            String modelVersion = extractString(json, "model_version");
            String schemaVersion = extractString(json, "schema_version");
            String sha256 = extractString(json, "sha256");
            int[] inputShape = extractIntArray(json, "input_shape");
            String inputDtype = extractString(json, "input_dtype");
            int outputClassCount = extractInt(json, "output_class_count");
            String outputDtype = extractString(json, "output_dtype");
            String preprocessingContractId = extractString(json, "preprocessing_contract_id");
            String labelsChecksum = extractString(json, "labels_checksum");
            List<String> nonDiagList = extractStringArray(json, "non_diagnostic_classes");
            String evidenceReportHash = extractOptionalString(json, "evidence_report_hash");

            if (modelId == null || modelVersion == null || sha256 == null
                    || inputShape.length == 0 || outputClassCount <= 0 || labelsChecksum == null) {
                throw new IllegalArgumentException("Manifest missing mandatory fields");
            }

            return new ModelManifest(
                    modelId,
                    modelVersion,
                    schemaVersion != null ? schemaVersion : "1.0.0",
                    sha256,
                    inputShape,
                    inputDtype != null ? inputDtype : "uint8",
                    outputClassCount,
                    outputDtype != null ? outputDtype : "float32",
                    preprocessingContractId != null ? preprocessingContractId : "cropdoc_preprocessing_v1",
                    labelsChecksum,
                    new HashSet<>(nonDiagList),
                    evidenceReportHash);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse ModelManifest JSON: " + e.getMessage(), e);
        }
    }

    @Nullable
    private static String extractString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Nullable
    private static String extractOptionalString(String json, String key) {
        String s = extractString(json, key);
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static int extractInt(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        throw new IllegalArgumentException("Missing or invalid integer key: " + key);
    }

    @NonNull
    private static int[] extractIntArray(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String content = matcher.group(1).trim();
            if (content.isEmpty()) {
                return new int[0];
            }
            String[] parts = content.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Integer.parseInt(parts[i].trim());
            }
            return result;
        }
        return new int[0];
    }

    @NonNull
    private static List<String> extractStringArray(String json, String key) {
        List<String> list = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String content = matcher.group(1).trim();
            if (!content.isEmpty()) {
                Matcher itemMatcher = Pattern.compile("\"([^\"]*)\"").matcher(content);
                while (itemMatcher.find()) {
                    list.add(itemMatcher.group(1));
                }
            }
        }
        return list;
    }
}
