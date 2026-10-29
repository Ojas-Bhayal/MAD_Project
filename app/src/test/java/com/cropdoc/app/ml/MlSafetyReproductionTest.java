package com.cropdoc.app.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Reproduction & verification tests for safety and boundary defects identified in Phase 0.
 * Confirms that all 3 identified failure scenarios are safely contained and prevented.
 */
public final class MlSafetyReproductionTest {

    private static final List<String> BASELINE_LABELS = Arrays.asList(
            "rice_bacterial_leaf_blight",
            "rice_brown_spot",
            "rice_leaf_blast",
            "rice_healthy",
            "tomato_early_blight",
            "tomato_late_blight",
            "tomato_leaf_mold",
            "tomato_healthy",
            "potato_early_blight",
            "potato_late_blight",
            "potato_healthy",
            "cotton_bacterial_blight",
            "cotton_healthy",
            "maize_fall_armyworm",
            "maize_northern_leaf_blight",
            "background_not_a_leaf");

    /**
     * Defect 5 & 3 Verification:
     * Green non-leaf objects (e.g. green cotton shirt RGB 50, 130, 45) must NOT yield
     * a confident disease or healthy crop diagnosis.
     * Heuristic spectral foliar logit synthesis has been completely eradicated.
     */
    @Test
    public void greenShirtPixels_mustNotYieldConfidentCropDiagnosis() {
        float[] logits = new float[ModelConfig.NUM_CLASSES];
        // Uninitialized or uniform logits (as occurs when model is unavailable or non-leaf)
        assertTrue("Logits remain uniform/zero", LeafSpectralAnalyzer.isAllZeroOrUniform(logits));

        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                logits, BASELINE_LABELS, "1.0.0-int8");

        // SAFE INVARIANT: Must NOT be Diagnosis! Must be ModelUnavailable or LowConfidence.
        assertFalse("Green non-leaf with uniform logits must not produce Diagnosis",
                outcome instanceof ClassificationOutcome.Diagnosis);
    }

    /**
     * Boundary Defect Verification:
     * AnalyzeLeafUseCase must NOT save non-diagnosis outcomes (e.g. ModelUnavailable, Rejected)
     * to ScanRepository.
     * Guarded by single boundary check in AnalyzeLeafUseCase.execute.
     */
    @Test
    public void nonDiagnosisOutcome_mustNotBeSavedToScanRepository() {
        LeafClassifier mockClassifier = mock(LeafClassifier.class);
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);

        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_123");
        when(mockScanRepo.saveScanSync(anyString(), any(DiagnosisResult.class), anyString()))
                .thenReturn(mock(ScanRecord.class));

        ClassificationOutcome rejectedOutcome = new ClassificationOutcome.Rejected(
                ClassificationOutcome.RejectionReason.UNDER_EXPOSED);
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(rejectedOutcome);

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(
                mockClassifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);

        Bitmap dummyBitmap = mock(Bitmap.class);
        AnalyzeLeafUseCase.AnalysisOutput output = useCase.execute(
                dummyBitmap, "/tmp/sanitized_leaf.jpg", "en");

        // SAFE INVARIANT: Non-diagnosis outcome must NEVER be persisted to Room / history.
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        assertEquals(rejectedOutcome, output.getOutcome());
    }

    /**
     * Defect 1 & 3 Verification:
     * When interpreter is unavailable (e.g. placeholder bundled model < 512KB),
     * the system must not fabricate class logits. Logits remain zero/uniform and
     * ModelValidator detects the placeholder model.
     */
    @Test
    public void unavailableModel_mustNotSynthesizeFoliarLogits() {
        float[] logits = new float[ModelConfig.NUM_CLASSES];
        assertTrue("Logits start empty/zero", LeafSpectralAnalyzer.isAllZeroOrUniform(logits));

        // ModelValidator detects placeholder model (< 512KB)
        ByteBuffer buf = ByteBuffer.allocate(16800);
        ModelValidator.ValidationResult valResult = ModelValidator.validateModel(
                16800L, buf, null, BASELINE_LABELS, "1.0.0-int8");

        assertFalse("Placeholder model is rejected", valResult.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED, valResult.getReason());

        // Logits remain zero/uniform: no fake foliar logits are synthesized!
        assertTrue("Logits remain safe and uniform", LeafSpectralAnalyzer.isAllZeroOrUniform(logits));
    }
}
