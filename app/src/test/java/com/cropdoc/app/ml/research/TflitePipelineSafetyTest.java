package com.cropdoc.app.ml.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.ml.ImageQualityGate;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.ml.policy.InferenceDecisionPolicy;
import com.cropdoc.app.ml.policy.InferencePolicyConfig;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.policy.PolicyRejectionDetail;
import com.cropdoc.app.ml.policy.SixClassResearchMapping;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.tensorflow.lite.Interpreter;

/**
 * Validates sequential gating, 6-class taxonomy mapping, cross-consistency enforcement,
 * fail-closed error handling, and zero advice/scan persistence on rejected outcomes.
 */
public final class TflitePipelineSafetyTest {

    private ImageQualityGate qualityGate;
    private InferenceDecisionPolicy policy;
    private Bitmap foliageBitmap;
    private ByteBuffer dummyBuffer;

    @Before
    public void setUp() {
        qualityGate = new ImageQualityGate();
        policy = new InferenceDecisionPolicy(InferencePolicyConfig.createWithCropDomainGate());

        dummyBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            dummyBuffer.put((byte) 128);
        }
        dummyBuffer.rewind();

        foliageBitmap = mock(Bitmap.class);
        when(foliageBitmap.getWidth()).thenReturn(224);
        when(foliageBitmap.getHeight()).thenReturn(224);
        org.mockito.Mockito.doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int greenPixel = (0xFF << 24) | (40 << 16) | (160 << 8) | 40;
            Arrays.fill(pixels, greenPixel);
            return null;
        }).when(foliageBitmap).getPixels(any(int[].class), any(int.class), any(int.class),
                any(int.class), any(int.class), any(int.class), any(int.class));
    }

    @Test
    public void sixClassMapping_exactTaxonomyAndGlobalIndicesAreVerified() {
        List<SixClassResearchMapping.ClassEntry> entries = SixClassResearchMapping.getEntries();
        assertEquals(6, entries.size());

        assertEntry(entries.get(0), 0, 0, "rice_bacterial_leaf_blight", "rice", false);
        assertEntry(entries.get(1), 1, 1, "rice_brown_spot", "rice", false);
        assertEntry(entries.get(2), 2, 2, "rice_leaf_blast", "rice", false);
        assertEntry(entries.get(3), 3, 3, "rice_healthy", "rice", true);
        assertEntry(entries.get(4), 4, 16, "groundnut_early_leaf_spot", "groundnut", false);
        assertEntry(entries.get(5), 5, 17, "groundnut_healthy", "groundnut", true);
    }

    @Test
    public void domainGate_unsupportedCrop_shortCircuitsDiseaseModel() throws Exception {
        ResearchModelExecutor domainExec = mock(ResearchModelExecutor.class);
        ResearchModelExecutor diseaseExec = mock(ResearchModelExecutor.class);
        when(domainExec.isAvailable()).thenReturn(true);
        // Output: [pRice=0.01, pGnut=0.01, pUnsupp=0.98]
        when(domainExec.execute(any())).thenReturn(new float[]{-3.0f, -3.0f, 6.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, domainExec, diseaseExec);
        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN,
                outcome.getDecisionResult().getRejectionDetail());
        verify(diseaseExec, never()).execute(any());
    }

    @Test
    public void domainGate_crossConsistencyViolation_failsClosed() throws Exception {
        ResearchModelExecutor domainExec = mock(ResearchModelExecutor.class);
        ResearchModelExecutor diseaseExec = mock(ResearchModelExecutor.class);
        when(domainExec.isAvailable()).thenReturn(true);
        when(diseaseExec.isAvailable()).thenReturn(true);
        // Domain confirms rice
        when(domainExec.execute(any())).thenReturn(new float[]{6.0f, -3.0f, -3.0f});
        // Disease predicts groundnut early leaf spot (index 4)
        when(diseaseExec.execute(any())).thenReturn(new float[]{-3.0f, -3.0f, -3.0f, -3.0f, 8.0f, -3.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, domainExec, diseaseExec);
        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);

        assertFalse(outcome.isAccepted());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.CROP_DOMAIN_MISMATCH,
                outcome.getDecisionResult().getRejectionDetail());
    }

    @Test
    public void nonDiagnosticOutcome_strictlyPreventsScanPersistenceAndAdvice() throws Exception {
        ResearchModelExecutor domainExec = mock(ResearchModelExecutor.class);
        ResearchModelExecutor diseaseExec = mock(ResearchModelExecutor.class);
        when(domainExec.isAvailable()).thenReturn(true);
        when(domainExec.execute(any())).thenReturn(new float[]{-2.0f, -2.0f, 6.0f});

        ResearchInferenceHarness harness = new ResearchInferenceHarness(
                qualityGate, policy, domainExec, diseaseExec);
        ResearchHarnessOutcome outcome = harness.processImage(foliageBitmap, dummyBuffer);
        assertFalse(outcome.isAccepted());
        assertFalse(outcome.getDecisionResult().isDiagnostic());

        // Verify that caller cannot extract disease class id or advice
        try {
            outcome.getDecisionResult().requireDiseaseClassId();
            org.junit.Assert.fail("requireDiseaseClassId must throw for non-diagnostic outcome");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        if (outcome.isAccepted()) {
            mockScanRepo.saveScanSync("test_user", null, "/tmp/img.jpg");
            mockAdviceRepo.getAdviceSync(outcome.getPredictedClassId(), "en");
        }

        verify(mockScanRepo, never()).saveScanSync(any(), any(), any());
        verify(mockAdviceRepo, never()).getAdviceSync(any(), any());
    }

    @Test
    public void liteRtExecutor_recordsLatencyAndValidatesInputSize() throws Exception {
        Interpreter mockInterp = mock(Interpreter.class);
        LiteRtResearchModelExecutor executor =
                new LiteRtResearchModelExecutor(mockInterp, 6, "test_model");

        assertTrue(executor.isAvailable());
        assertEquals("test_model", executor.getModelIdentifier());
        assertEquals(6, executor.getOutputClasses());

        // Under-sized buffer must be rejected
        ByteBuffer tooSmall = ByteBuffer.allocate(100);
        try {
            executor.execute(tooSmall);
            org.junit.Assert.fail("Expected IllegalArgumentException for small buffer");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        // Full NCHW buffer executes and measures latency
        ByteBuffer valid = ByteBuffer.allocateDirect(LiteRtResearchModelExecutor.EXPECTED_NCHW_BYTES);
        float[] out = executor.execute(valid);
        assertEquals(6, out.length);
        assertTrue(executor.getLastExecutionLatencyMs() >= 1L);

        executor.close();
        assertFalse(executor.isAvailable());
    }

    private static void assertEntry(
            SixClassResearchMapping.ClassEntry entry,
            int localIdx,
            int globalIdx,
            String classId,
            String crop,
            boolean healthy) {
        assertEquals(localIdx, entry.localIndex);
        assertEquals(globalIdx, entry.globalIndex);
        assertEquals(classId, entry.classId);
        assertEquals(crop, entry.crop);
        assertEquals(healthy, entry.isHealthy);
    }
}
