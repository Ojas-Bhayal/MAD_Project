package com.cropdoc.app.ml.research;

import android.graphics.Bitmap;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.ml.policy.CropDomain;
import com.cropdoc.app.ml.policy.InferenceDecisionPolicy;
import com.cropdoc.app.ml.policy.InferencePolicyConfig;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.policy.PolicyRejectionDetail;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolated unit tests validating that the research two-model inference harness
 * strictly guarantees fail-closed safety, sequential gating, and zero caller metadata bypass.
 */
public class ResearchInferenceHarnessTest {

    private ImageQualityGate qualityGate;
    private InferenceDecisionPolicy policy;
    @Mock private ResearchModelExecutor mockDomainExecutor;
    @Mock private ResearchModelExecutor mockDiseaseExecutor;

    private Bitmap foliageBitmap;
    private Bitmap nonLeafSoilBitmap;
    private ByteBuffer dummyBuffer;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        qualityGate = new ImageQualityGate();
        policy = new InferenceDecisionPolicy(InferencePolicyConfig.createWithCropDomainGate());

        when(mockDomainExecutor.isAvailable()).thenReturn(true);
        when(mockDiseaseExecutor.isAvailable()).thenReturn(true);

        dummyBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            dummyBuffer.put((byte) 128);
        }
        dummyBuffer.rewind();

        // Foliage bitmap mock: green pixels (RGB 40, 160, 40)
        foliageBitmap = mock(Bitmap.class);
        when(foliageBitmap.getWidth()).thenReturn(224);
        when(foliageBitmap.getHeight()).thenReturn(224);
        doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int greenPixel = (0xFF << 24) | (40 << 16) | (160 << 8) | 40;
            Arrays.fill(pixels, greenPixel);
            return null;
        }).when(foliageBitmap).getPixels(any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        // Non-leaf soil bitmap: brown pixels (RGB 136, 112, 89)
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
    public void testValidRiceLeaf_acceptedDiseaseDiagnosis() throws Exception {
        // Domain gate outputs logits favoring Rice: [6.0, -2.0, -2.0]
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{6.0f, -2.0f, -2.0f});
        // Disease model outputs logits favoring rice_bacterial_leaf_blight (idx 0): [6.0, -1.0, -1.0, -1.0, -1.0, -1.0]
        when(mockDiseaseExecutor.execute(any())).thenReturn(new float[]{6.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertTrue(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.DECISION_POLICY, outcome.getTerminalStage());
        assertEquals(CropDomain.SUPPORTED_RICE, outcome.getDetectedDomain());
        assertEquals("rice_bacterial_leaf_blight", outcome.getPredictedClassId());
        assertTrue(outcome.getDiseaseConfidence() > 0.90f);
    }

    @Test
    public void testValidGroundnutLeaf_acceptedHealthyOutcome() throws Exception {
        // Domain gate outputs logits favoring Groundnut: [-2.0, 6.0, -2.0]
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{-2.0f, 6.0f, -2.0f});
        // Disease model outputs logits favoring groundnut_healthy (idx 5): [-1.0, -1.0, -1.0, -1.0, -1.0, 6.0f]
        when(mockDiseaseExecutor.execute(any())).thenReturn(new float[]{-1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 6.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertTrue(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.DECISION_POLICY, outcome.getTerminalStage());
        assertEquals(CropDomain.SUPPORTED_GROUNDNUT, outcome.getDetectedDomain());
        assertEquals("groundnut_healthy", outcome.getPredictedClassId());
        assertTrue(outcome.getDecisionResult().isHealthy());
        assertFalse(outcome.getDecisionResult().isDiseaseDiagnosis());
    }

    @Test
    public void testUnsupportedCropLeaf_rejectedBeforeDiseaseModelInvoked() throws Exception {
        // Domain gate detects unsupported crop (idx 2): [-2.0, -2.0, 6.0]
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{-2.0f, -2.0f, 6.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN, outcome.getDecisionResult().getRejectionDetail());
        assertNull(outcome.getPredictedClassId());

        // CRITICAL INVARIANT: Disease classifier must NEVER be executed for unsupported crop!
        verify(mockDiseaseExecutor, never()).execute(any());
    }

    @Test
    public void testCrossDomainMismatch_rejectedByPolicy() throws Exception {
        // Domain gate detects Rice
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{6.0f, -2.0f, -2.0f});
        // Disease model mistakenly predicts groundnut_early_leaf_spot (idx 4)
        when(mockDiseaseExecutor.execute(any())).thenReturn(new float[]{-1.0f, -1.0f, -1.0f, -1.0f, 6.0f, -1.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_MISMATCH, outcome.getDecisionResult().getRejectionDetail());
    }

    @Test
    public void testNonLeafSoilImage_rejectedAtFoliarGateBeforeAnyModelExecution() throws Exception {
        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(nonLeafSoilBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.FOLIAR_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                outcome.getDecisionResult().getRejectionDetail());

        // Zero model execution for non-leaf input
        verify(mockDomainExecutor, never()).execute(any());
        verify(mockDiseaseExecutor, never()).execute(any());
    }

    @Test
    public void testMissingDomainModel_failsClosed() throws Exception {
        when(mockDomainExecutor.isAvailable()).thenReturn(false);

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_GATE_NOT_PRESENT,
                outcome.getDecisionResult().getRejectionDetail());
        verify(mockDiseaseExecutor, never()).execute(any());
    }

    @Test
    public void testMissingDiseaseModel_failsClosed() throws Exception {
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{6.0f, -2.0f, -2.0f});
        when(mockDiseaseExecutor.isAvailable()).thenReturn(false);

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.MODEL_NOT_AVAILABLE, outcome.getDecisionResult().getRejectionDetail());
    }

    @Test
    public void testMalformedOutputWithNaN_failsClosed() throws Exception {
        when(mockDomainExecutor.execute(any())).thenReturn(new float[]{6.0f, -2.0f, -2.0f});
        when(mockDiseaseExecutor.execute(any())).thenReturn(new float[]{Float.NaN, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, mockDomainExecutor, mockDiseaseExecutor);

        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.NON_FINITE_VALUES, outcome.getDecisionResult().getRejectionDetail());
    }
}
