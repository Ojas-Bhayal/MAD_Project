package com.cropdoc.app.ml.policy;

import android.graphics.Bitmap;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ml.ClassificationOutcome;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.LeafGate;
import com.cropdoc.app.ml.ModelConfig;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit test suite validating that the Crop-Domain Gate prototype
 * maintains strict fail-closed safety guarantees when integrated into the inference pipeline.
 */
public class CropDomainGateSafetyTest {

    private ImageQualityGate qualityGate;
    private LeafGate mockLeafGate;
    private CropDomainGate mockCropDomainGate;
    private AdviceRepository mockAdviceRepository;
    private ScanRepository mockScanRepository;
    private AuthRepository mockAuthRepository;

    private InferenceDecisionPolicy policyWithGate;
    private EndToEndSafetyPipeline pipeline;
    private ByteBuffer inputBuffer;
    private Bitmap foliageBitmap;
    private Bitmap nonLeafSoilBitmap;

    @Before
    public void setUp() {
        qualityGate = new ImageQualityGate();
        mockLeafGate = mock(LeafGate.class);
        mockCropDomainGate = mock(CropDomainGate.class);
        mockAdviceRepository = mock(AdviceRepository.class);
        mockScanRepository = mock(ScanRepository.class);
        mockAuthRepository = mock(AuthRepository.class);

        when(mockLeafGate.getState()).thenReturn(LeafGate.GateState.VALIDATED);
        when(mockLeafGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(LeafGate.GateResult.accept("Valid leaf"));
        when(mockCropDomainGate.getState()).thenReturn(CropDomainGate.GateState.VALIDATED);
        when(mockAuthRepository.getCurrentUserId()).thenReturn("user_test_42");
        when(mockScanRepository.saveScanSync(anyString(), any(DiagnosisResult.class), anyString()))
                .thenReturn(mock(ScanRecord.class));
        when(mockAdviceRepository.getAdviceSync(eq("rice_bacterial_leaf_blight"), anyString()))
                .thenReturn(mock(CropDiseaseAdvice.class));

        InferencePolicyConfig config = InferencePolicyConfig.createWithCropDomainGate();
        policyWithGate = new InferenceDecisionPolicy(config);
        pipeline = new EndToEndSafetyPipeline(
                qualityGate, policyWithGate, mockAdviceRepository, mockScanRepository, mockAuthRepository);

        inputBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            inputBuffer.put((byte) 128);
        }
        inputBuffer.rewind();
        initMockBitmaps();
    }

    private void initMockBitmaps() {
        foliageBitmap = mock(Bitmap.class);
        when(foliageBitmap.getWidth()).thenReturn(224);
        when(foliageBitmap.getHeight()).thenReturn(224);
        doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int greenPixel = (0xFF << 24) | (40 << 16) | (160 << 8) | 40;
            Arrays.fill(pixels, greenPixel);
            return null;
        }).when(foliageBitmap).getPixels(any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        nonLeafSoilBitmap = mock(Bitmap.class);
        when(nonLeafSoilBitmap.getWidth()).thenReturn(224);
        when(nonLeafSoilBitmap.getHeight()).thenReturn(224);
        doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int soilPixel = (0xFF << 24) | (136 << 16) | (112 << 8) | 89;
            Arrays.fill(pixels, soilPixel);
            return null;
        }).when(nonLeafSoilBitmap).getPixels(
                any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testWhenDomainGateNotPresent_failsClosedModelUnavailable() {
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{5.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, null, runner, "rice", "test.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_GATE_NOT_PRESENT,
                output.getDecisionResult().getRejectionDetail());
        assertTrue(output.getClassificationOutcome() instanceof ClassificationOutcome.ModelUnavailable);
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(mockScanRepository, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void testWhenDomainGateUnvalidated_failsClosedModelUnavailable() {
        when(mockCropDomainGate.getState()).thenReturn(CropDomainGate.GateState.UNVALIDATED);
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{5.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "rice", "test.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_GATE_UNVALIDATED,
                output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        verify(mockScanRepository, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void testWhenDomainGateRejectsUnsupportedCrop_failsClosedOutOfDomain() {
        when(mockCropDomainGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(CropDomainResult.rejectedUnsupported(0.98f, 0.10f, "Cotton or tomato foliage detected"));
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "cotton", "cotton.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN, output.getDecisionResult().getRejectionDetail());
        assertTrue(output.getClassificationOutcome() instanceof ClassificationOutcome.Rejected);
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(mockScanRepository, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void testWhenDomainGateUncertain_failsClosedInconclusive() {
        when(mockCropDomainGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(CropDomainResult.rejectedUncertain(0.45f, 0.85f, "Ambiguous crop species foliage"));
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "rice", "test.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_UNCERTAIN, output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        verify(mockScanRepository, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void testWhenDomainGateClassifiesRiceAndDiseaseMatches_acceptedWithAdvice() {
        when(mockCropDomainGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(CropDomainResult.acceptedRice(0.99f, 0.05f, "Rice foliage confirmed"));
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "rice", "rice_blight.jpg", "en");

        assertTrue(output.getDecisionResult().isAccepted());
        assertTrue(output.getDecisionResult().isDiseaseDiagnosis());
        assertEquals("rice_bacterial_leaf_blight", output.getDecisionResult().getClassId());
        assertTrue(output.getClassificationOutcome() instanceof ClassificationOutcome.Diagnosis);
        assertNotNull(output.getScanRecord());
        assertNotNull(output.getAdvice());
        verify(mockScanRepository).saveScanSync(eq("user_test_42"), any(DiagnosisResult.class), eq("rice_blight.jpg"));
    }

    @Test
    public void testWhenDomainGateClassifiesGroundnutAndHealthy_acceptedWithoutChemicalAdvice() {
        when(mockCropDomainGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(CropDomainResult.acceptedGroundnut(0.98f, 0.08f, "Groundnut foliage confirmed"));
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{-1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 6.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate,
                runner, "groundnut", "gnut_healthy.jpg", "en");

        assertTrue(output.getDecisionResult().isAccepted());
        assertTrue(output.getDecisionResult().isHealthy());
        assertFalse(output.getDecisionResult().isDiseaseDiagnosis());
        assertEquals("groundnut_healthy", output.getDecisionResult().getClassId());
        assertNotNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(mockAdviceRepository, never()).getAdviceSync(anyString(), anyString());
    }

    @Test
    public void testWhenDomainGateSaysRiceButDiseaseClassifierSaysGroundnut_rejectedCrossMismatch() {
        when(mockCropDomainGate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(CropDomainResult.acceptedRice(0.99f, 0.05f, "Rice foliage confirmed"));
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{-1.0f, -1.0f, -1.0f, -1.0f, 6.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "rice", "mismatch.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_MISMATCH, output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(mockScanRepository, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void testNonLeafSoilImage_rejectedAtFoliarGateBeforeDomainGate() {
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f};
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                nonLeafSoilBitmap, inputBuffer, mockLeafGate, mockCropDomainGate, runner, "rice", "soil.jpg", "en");

        assertFalse(output.getDecisionResult().isAccepted());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(mockCropDomainGate, never()).evaluate(any(Bitmap.class), any(ByteBuffer.class));
    }
}
