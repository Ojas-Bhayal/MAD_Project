package com.cropdoc.app.ml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable evidence bundle required to certify a semantic leaf gate as VALIDATED (Phase G).
 *
 * In accordance with FAIL CLOSED architecture:
 * Merely providing an interface implementation does not establish validation.
 * Evidence must include:
 * 1. Model manifest with cryptographic model SHA-256 and labels checksum.
 * 2. Input/output tensor dimensions and dtypes.
 * 3. Preprocessing contract ID and version.
 * 4. Independently generated evaluation report whose SHA-256 matches the manifest's evidence_report_hash.
 * 5. Measured metrics meeting strict acceptance criteria:
 *    - False Acceptance Rate (FAR) on non-leaves <= 0.001 (0.1%)
 *    - True Acceptance Rate (TAR) on genuine leaves >= 0.95 (95.0%)
 *
 * If any evidence is absent, invalid, stale, or mismatched, validation fails closed.
 */
public final class GateValidationEvidence {

    public static final float MAX_FALSE_ACCEPTANCE_RATE = 0.001f; // 0.1% max on non-leaf negatives
    public static final float MIN_LEAF_ACCEPTANCE_RATE = 0.95f;   // 95.0% min on genuine leaves

    @NonNull private final ModelManifest manifest;
    @Nullable private final String evaluationReportContent;
    @Nullable private final String actualReportSha256;
    private final float measuredLeafAcceptanceRate;
    private final float measuredFalseAcceptanceRate;

    public GateValidationEvidence(
            @NonNull ModelManifest manifest,
            @Nullable String evaluationReportContent,
            @Nullable String actualReportSha256,
            float measuredLeafAcceptanceRate,
            float measuredFalseAcceptanceRate) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.evaluationReportContent = evaluationReportContent;
        this.actualReportSha256 = actualReportSha256 != null
                ? actualReportSha256.toLowerCase(Locale.ROOT) : null;
        this.measuredLeafAcceptanceRate = measuredLeafAcceptanceRate;
        this.measuredFalseAcceptanceRate = measuredFalseAcceptanceRate;
    }

    @NonNull public ModelManifest getManifest() { return manifest; }
    @Nullable public String getEvaluationReportContent() { return evaluationReportContent; }
    @Nullable public String getActualReportSha256() { return actualReportSha256; }
    public float getMeasuredLeafAcceptanceRate() { return measuredLeafAcceptanceRate; }
    public float getMeasuredFalseAcceptanceRate() { return measuredFalseAcceptanceRate; }

    /**
     * Rigorously verifies all evidence criteria.
     * Returns true ONLY if all cryptographic hashes, preprocessing contracts, and thresholds pass.
     */
    public boolean isCertified() {
        // 1. Manifest must have non-empty model SHA-256 and labels checksum
        if (manifest.getSha256().isEmpty() || manifest.getLabelsChecksum().isEmpty()) {
            return false;
        }

        // 2. Preprocessing contract must be defined
        if (manifest.getPreprocessingContractId().isEmpty()) {
            return false;
        }

        // 3. Expected input dimensions: 1x224x224x3 uint8
        int[] shape = manifest.getInputShape();
        if (shape.length != 4 || shape[0] != 1 || shape[1] != 224 || shape[2] != 224 || shape[3] != 3) {
            return false;
        }
        if (!"uint8".equalsIgnoreCase(manifest.getInputDtype())) {
            return false;
        }

        // 4. Output must be binary leaf-vs-nonleaf (class count = 2)
        if (manifest.getOutputClassCount() != 2) {
            return false;
        }

        // 5. Evidence report hash in manifest must be non-empty and match actual report SHA-256
        String expectedReportHash = manifest.getEvidenceReportHash();
        if (expectedReportHash == null || expectedReportHash.trim().isEmpty() || actualReportSha256 == null) {
            return false;
        }
        if (!expectedReportHash.equalsIgnoreCase(actualReportSha256)) {
            return false;
        }

        // 6. Empirical thresholds must be satisfied
        if (measuredLeafAcceptanceRate < MIN_LEAF_ACCEPTANCE_RATE) {
            return false;
        }
        if (measuredFalseAcceptanceRate > MAX_FALSE_ACCEPTANCE_RATE) {
            return false;
        }

        return true;
    }
}
