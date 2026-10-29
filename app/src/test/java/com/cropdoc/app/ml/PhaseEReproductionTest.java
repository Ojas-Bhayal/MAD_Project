package com.cropdoc.app.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Phase 0 Reproduction tests demonstrating gaps G1, G2, G3, G4.
 * In the baseline codebase of Phase E, these tests demonstrate the defects.
 */
public final class PhaseEReproductionTest {

    private static final List<String> TAXONOMY_WITH_NON_LEAF = Arrays.asList(
            "rice_bacterial_leaf_blight",
            "rice_brown_spot",
            "rice_leaf_blast",
            "rice_healthy",
            "tomato_early_blight",
            "tomato_late_blight",
            "tomato_yellow_leaf_curl_virus",
            "tomato_healthy",
            "potato_early_blight",
            "potato_late_blight",
            "potato_healthy",
            "cotton_bacterial_blight",
            "cotton_healthy",
            "maize_common_rust",
            "maize_northern_leaf_blight",
            "background_not_a_leaf"); // index 15: non-diagnostic class

    /**
     * Gap G1 Reproduction:
     * When logits strongly favor 'background_not_a_leaf' (confidence > 90%),
     * ConfidenceCalibrator must NOT return Diagnosis, and AnalyzeLeafUseCase must NOT
     * treat it as CONFIDENT_DISEASE or persist it to ScanRepository.
     * In current code, this FAILS because ConfidenceCalibrator returns Diagnosis,
     * which AnalyzeLeafUseCase marks as CONFIDENT_DISEASE and saves to Room.
     */
    @Test
    public void g1_nonLeafClass_mustNeverProduceDiagnosisOrPersistToHistory() {
        float[] logits = new float[16];
        logits[15] = 12.0f; // Strongly predicts background_not_a_leaf

        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                logits, TAXONOMY_WITH_NON_LEAF, "1.0.0-int8");

        // GAP G1: Current evaluateLogitsOutcome produces Diagnosis("background_not_a_leaf", ...)!
        // Safe Invariant: Non-diagnostic classes must NEVER produce a Diagnosis outcome!
        assertFalse("Non-leaf class top-1 must NOT produce Diagnosis",
                outcome instanceof ClassificationOutcome.Diagnosis);

        // Verify boundary behavior in AnalyzeLeafUseCase
        LeafClassifier mockClassifier = mock(LeafClassifier.class);
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(outcome);

        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_test");

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(
                mockClassifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);

        useCase.execute(mock(Bitmap.class), "/tmp/scan.jpg", "en");

        // Safe Invariant: Non-leaf class must NEVER be saved to ScanRepository!
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    /**
     * Gap G2 Reproduction:
     * A missing or unvalidated semantic gate must FAIL CLOSED with GateUnavailable.
     * In current code, DefaultLeafGate accepts normally lit images without validating
     * whether the image is actually a leaf or human photo.
     */
    @Test
    public void g2_missingOrUnvalidatedSemanticGate_mustFailClosedWithGateUnavailable() {
        DefaultLeafGate gate = new DefaultLeafGate();
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        java.nio.ByteBuffer tensorBuffer = java.nio.ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.PIXEL_COUNT; i++) {
            tensorBuffer.put((byte) 100);
            tensorBuffer.put((byte) 120);
            tensorBuffer.put((byte) 90);
        }

        LeafGate.GateResult result = gate.evaluate(mockBitmap, tensorBuffer);

        // GAP G2: DefaultLeafGate currently returns ACCEPT for normally lit non-leaves!
        // Safe Invariant: Without a validated semantic gate, it must NOT return ACCEPT.
        assertNotEquals("Unvalidated gate must NOT silently accept images",
                LeafGate.GateDecision.ACCEPT, result.getDecision());
    }

    /**
     * Gap G3 Reproduction:
     * Model integrity verification requires a model manifest JSON and digest comparison.
     * In current code, no manifest exists in assets/models and computeLabelOrderChecksum
     * is never compared against any expected value.
     */
    @Test
    public void g3_modelIntegrity_requiresManifestAndDigestVerification() throws Exception {
        File manifestFile = new File("src/main/assets/models/cropdoc_leaf_v1_manifest.json");
        assertTrue("Model manifest JSON must exist beside model", manifestFile.exists());

        File labelsFile = new File("src/main/assets/models/labels.txt");
        assertTrue("Labels file must exist beside manifest", labelsFile.exists());

        String manifestContent = new String(
                java.nio.file.Files.readAllBytes(manifestFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        ModelManifest manifest = ModelManifest.parse(manifestContent);

        List<String> labels = java.nio.file.Files.readAllLines(
                labelsFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        List<String> nonBlankLabels = new java.util.ArrayList<>();
        for (String l : labels) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) {
                nonBlankLabels.add(trimmed);
            }
        }

        long crc = ModelValidator.computeLabelOrderChecksum(nonBlankLabels);
        String expectedHex = Long.toHexString(crc).toLowerCase(java.util.Locale.US);

        assertEquals("Manifest labels_checksum must match genuine CRC32",
                expectedHex, manifest.getLabelsChecksum().toLowerCase(java.util.Locale.US));
    }

    /**
     * Gap G4 Reproduction:
     * AnalyzeLeafUseCase currently hardcodes entropy=0f, leafRatio=1f, duration=0L.
     * Genuine measured metadata from inference must be preserved.
     */
    @Test
    public void g4_analyzeLeafUseCase_mustNotStoreHardcodedInferenceMetadata() {
        LeafClassifier mockClassifier = mock(LeafClassifier.class);
        ClassificationOutcome.Diagnosis diag = new ClassificationOutcome.Diagnosis(
                "tomato_early_blight", 0.95f, "1.0.0-int8", 0.28f, 42L, 0.88f);
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(diag);

        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_test");

        org.mockito.ArgumentCaptor<DiagnosisResult> captor =
                org.mockito.ArgumentCaptor.forClass(DiagnosisResult.class);
        when(mockScanRepo.saveScanSync(anyString(), captor.capture(), anyString()))
                .thenReturn(mock(ScanRecord.class));

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(
                mockClassifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);

        useCase.execute(mock(Bitmap.class), "/tmp/scan.jpg", "en");

        DiagnosisResult saved = captor.getValue();
        assertNotNull(saved);

        // GAP G4 verification: AnalyzeLeafUseCase must propagate measured inference metadata,
        // never overwrite with hardcoded 0L duration or 0f entropy.
        assertEquals("Inference duration must match measured duration", 42L, saved.getInferenceTimeMs());
        assertEquals("Entropy must match measured entropy", 0.28f, saved.getNormalizedEntropy(), 1e-6f);
        assertEquals("Leaf ratio must match measured leaf ratio", 0.88f, saved.getLeafPixelRatio(), 1e-6f);
    }
}
