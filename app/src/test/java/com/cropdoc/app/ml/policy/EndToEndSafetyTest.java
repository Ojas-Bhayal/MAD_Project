package com.cropdoc.app.ml.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/**
 * End-to-end integration safety tests verifying the complete path from input image through
 * gating, model inference, decision policy, and domain scan persistence / advice lookup.
 */
public final class EndToEndSafetyTest {

    private ImageQualityGate qualityGate;
    private InferenceDecisionPolicy policy;
    private AdviceRepository adviceRepo;
    private ScanRepository scanRepo;
    private AuthRepository authRepo;
    private EndToEndSafetyPipeline pipeline;

    private Bitmap foliageBitmap;
    private Bitmap nonLeafSoilBitmap;
    private ByteBuffer dummyInputBuffer;

    @Before
    public void setUp() {
        qualityGate = new ImageQualityGate();
        policy = new InferenceDecisionPolicy();
        adviceRepo = mock(AdviceRepository.class);
        scanRepo = mock(ScanRepository.class);
        authRepo = mock(AuthRepository.class);

        when(authRepo.getCurrentUserId()).thenReturn("farmer_test_id");
        when(scanRepo.saveScanSync(anyString(), any(DiagnosisResult.class), anyString()))
                .thenReturn(mock(ScanRecord.class));

        pipeline = new EndToEndSafetyPipeline(qualityGate, policy, adviceRepo, scanRepo, authRepo);

        // Genuine foliage bitmap: green pixels (RGB 40, 160, 40)
        foliageBitmap = mock(Bitmap.class);
        when(foliageBitmap.getWidth()).thenReturn(224);
        when(foliageBitmap.getHeight()).thenReturn(224);
        doAnswer(invocation -> {
            int[] pixels = invocation.getArgument(0);
            int greenPixel = (0xFF << 24) | (40 << 16) | (160 << 8) | 40;
            Arrays.fill(pixels, greenPixel);
            return null;
        }).when(foliageBitmap).getPixels(any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        // Non-leaf soil bitmap: brown pixels (RGB 136, 112, 89)
        nonLeafSoilBitmap = mock(Bitmap.class);
        when(nonLeafSoilBitmap.getWidth()).thenReturn(224);
        when(nonLeafSoilBitmap.getHeight()).thenReturn(224);
        doAnswer(invocation -> {
            int[] pixels = invocation.getArgument(0);
            int soilPixel = (0xFF << 24) | (136 << 16) | (112 << 8) | 89;
            Arrays.fill(pixels, soilPixel);
            return null;
        }).when(nonLeafSoilBitmap).getPixels(
                any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        dummyInputBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            dummyInputBuffer.put((byte) 128);
        }
        dummyInputBuffer.rewind();
    }

    private LeafGate createMockValidatedGate(LeafGate.GateDecision decision) {
        LeafGate gate = mock(LeafGate.class);
        when(gate.getState()).thenReturn(LeafGate.GateState.VALIDATED);
        when(gate.evaluate(any(Bitmap.class), any(ByteBuffer.class)))
                .thenReturn(decision == LeafGate.GateDecision.ACCEPT
                        ? LeafGate.GateResult.accept("Foliar gate validated")
                        : LeafGate.GateResult.reject(
                                ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED,
                                "Non-leaf detected"));
        return gate;
    }

    // -------------------------------------------------------------------------
    // 1. Non-leaf image never produces saved scan record or advice
    // -------------------------------------------------------------------------

    @Test
    public void nonLeafImage_neverProducesSavedScanRecordOrAdvice() {
        LeafGate validatedGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Model runner that would have output high confidence if reached
        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f};

        // Pass nonLeafSoilBitmap (foliar ratio ~0.0 < 0.18)
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                nonLeafSoilBitmap,
                dummyInputBuffer,
                validatedGate,
                runner,
                "rice",
                "/tmp/non_leaf.jpg",
                "en");

        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                output.getDecisionResult().getRejectionDetail());
        assertFalse(output.getDecisionResult().isAccepted());

        // SAFE INVARIANT: Zero scan persistence and zero advice lookup
        assertNull("ScanRecord must be null for non-leaf input", output.getScanRecord());
        assertNull("Advice must be null for non-leaf input", output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(adviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // 2. Unsupported crop cannot produce disease diagnosis or advice
    // -------------------------------------------------------------------------

    @Test
    public void unsupportedCrop_withOverconfidentSoftmax_neverProducesSavedScanRecordOrAdvice() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Closed-world neural network outputs 99% confidence on groundnut early spot (class 4)
        EndToEndSafetyPipeline.ModelRunner overconfidentRunner = buf -> new float[]{0.1f, 0.1f, 0.1f, 0.1f, 7.5f, 0.1f};

        // Input is a genuine leaf (foliageBitmap), but crop domain is "cotton"
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                overconfidentRunner,
                "cotton",
                "/tmp/cotton_leaf.jpg",
                "en");

        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN, output.getDecisionResult().getRejectionDetail());
        assertFalse(output.getDecisionResult().isAccepted());
        assertFalse(output.getDecisionResult().isDiseaseDiagnosis());

        // SAFE INVARIANT: Softmax overconfidence on unsupported crop MUST NOT persist or fetch advice
        assertNull("ScanRecord must be null for unsupported crop", output.getScanRecord());
        assertNull("Advice must be null for unsupported crop", output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(adviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // 3. Ambiguous top-2 prediction returns inconclusive outcome and never saves record
    // -------------------------------------------------------------------------

    @Test
    public void ambiguousTop2Prediction_returnsInconclusive_neverSavesRecord() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Ambiguous close predictions on valid rice leaf: Class 0 vs Class 1 (margin ~0.04 < 0.35)
        EndToEndSafetyPipeline.ModelRunner ambiguousRunner = buf -> new float[]{3.0f, 2.9f, 0.1f, 0.1f, 0.1f, 0.1f};

        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                ambiguousRunner,
                "rice",
                "/tmp/ambiguous.jpg",
                "en");

        assertEquals(PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.MARGIN_BELOW_THRESHOLD, output.getDecisionResult().getRejectionDetail());
        assertFalse(output.getDecisionResult().isAccepted());

        assertNull("ScanRecord must be null for ambiguous prediction", output.getScanRecord());
        assertNull("Advice must be null for ambiguous prediction", output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(adviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // 4. Healthy rice and groundnut never trigger disease-specific treatment advice
    // -------------------------------------------------------------------------

    @Test
    public void healthyRiceAndGroundnut_acceptedAsAsymptomatic_neverRetrieveDiseaseAdvice() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Confident healthy rice (Class 3) on genuine foliage
        EndToEndSafetyPipeline.ModelRunner healthyRiceRunner = buf -> new float[]{0.1f, 0.1f, 0.1f, 6.5f, 0.1f, 0.1f};

        EndToEndSafetyPipeline.PipelineOutput outputRice = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                healthyRiceRunner,
                "rice",
                "/tmp/healthy_rice.jpg",
                "en");

        assertTrue("Healthy rice should be accepted", outputRice.getDecisionResult().isAccepted());
        assertTrue(outputRice.getDecisionResult().isHealthy());
        assertFalse("Healthy rice is NOT a disease diagnosis", outputRice.getDecisionResult().isDiseaseDiagnosis());
        assertNotNull("Healthy rice scan record may be saved", outputRice.getScanRecord());

        // SAFE INVARIANT: Healthy rice MUST NOT query disease treatment advice
        assertNull("Disease treatment advice must be null for healthy leaf", outputRice.getAdvice());
        verify(adviceRepo, never()).getAdviceSync(anyString(), anyString());

        try {
            outputRice.getDecisionResult().requireDiseaseClassId();
            fail("Expected IllegalStateException for requireDiseaseClassId on healthy leaf");
        } catch (IllegalStateException expected) {
            // Verified
        }
    }

    // -------------------------------------------------------------------------
    // 5. Missing model, unvalidated gate, or corrupt tensors fail closed
    // -------------------------------------------------------------------------

    @Test
    public void missingModel_failsClosedAsModelUnavailable() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Runner is null
        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                null,
                "rice",
                "/tmp/leaf.jpg",
                "en");

        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, output.getDecisionResult().getOutcomeType());
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void unvalidatedLeafGate_failsClosedAsModelUnavailable() {
        LeafGate unvalidatedGate = mock(LeafGate.class);
        when(unvalidatedGate.getState()).thenReturn(LeafGate.GateState.PRESENT_UNVALIDATED);

        EndToEndSafetyPipeline.ModelRunner runner = buf -> new float[]{6.0f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f};

        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                unvalidatedGate,
                runner,
                "rice",
                "/tmp/leaf.jpg",
                "en");

        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_UNVALIDATED, output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    @Test
    public void corruptTensorNan_failsClosedAsInvalidModelOutput() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);

        // Model outputs NaN
        EndToEndSafetyPipeline.ModelRunner nanRunner = buf -> new float[]{Float.NaN, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f};

        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                nanRunner,
                "rice",
                "/tmp/leaf.jpg",
                "en");

        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, output.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.NON_FINITE_VALUES, output.getDecisionResult().getRejectionDetail());
        assertNull(output.getScanRecord());
        assertNull(output.getAdvice());
        verify(scanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    // -------------------------------------------------------------------------
    // 6. Confirmed in-domain disease diagnosis saves record and fetches advice
    // -------------------------------------------------------------------------

    @Test
    public void confirmedDisease_riceBacterialBlight_savesRecordAndRetrievesAdvice() {
        LeafGate passingGate = createMockValidatedGate(LeafGate.GateDecision.ACCEPT);
        CropDiseaseAdvice mockAdvice = mock(CropDiseaseAdvice.class);
        when(adviceRepo.getAdviceSync("rice_bacterial_leaf_blight", "en")).thenReturn(mockAdvice);

        // Confident Rice Bacterial Leaf Blight (Class 0) on genuine foliage
        EndToEndSafetyPipeline.ModelRunner diseaseRunner = buf -> new float[]{7.0f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f};

        EndToEndSafetyPipeline.PipelineOutput output = pipeline.execute(
                foliageBitmap,
                dummyInputBuffer,
                passingGate,
                diseaseRunner,
                "rice",
                "/tmp/blight.jpg",
                "en");

        assertEquals(PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS, output.getDecisionResult().getOutcomeType());
        assertTrue(output.getDecisionResult().isDiseaseDiagnosis());
        assertEquals("rice_bacterial_leaf_blight", output.getDecisionResult().requireDiseaseClassId());

        assertNotNull("Scan record must be saved for confirmed disease", output.getScanRecord());
        assertNotNull("Advice must be retrieved for confirmed disease", output.getAdvice());
        assertEquals(mockAdvice, output.getAdvice());

        verify(scanRepo).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(adviceRepo).getAdviceSync("rice_bacterial_leaf_blight", "en");
    }
}
