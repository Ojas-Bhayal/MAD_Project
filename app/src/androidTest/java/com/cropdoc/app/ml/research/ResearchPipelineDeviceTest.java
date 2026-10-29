package com.cropdoc.app.ml.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android instrumentation tests validating full research pipeline execution on-device.
 * Executes real native LiteRT models, verifies sequential gating, and ensures non-diagnostic isolation.
 */
@RunWith(AndroidJUnit4.class)
public final class ResearchPipelineDeviceTest {

    private static final String DOMAIN_GATE_ASSET =
            "research_models/crop_domain_gate_fp32.tflite";
    private static final String SIX_CLASS_ASSET =
            "research_models/unified_six_class_fp32.tflite";

    private Context context;
    private LiteRtResearchModelExecutor domainExecutor;
    private LiteRtResearchModelExecutor diseaseExecutor;
    private ResearchInferenceHarness harness;
    private ByteBuffer reusableByteBuffer;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        domainExecutor = LiteRtResearchModelExecutor.fromAsset(context, DOMAIN_GATE_ASSET, 3, null);
        diseaseExecutor = LiteRtResearchModelExecutor.fromAsset(context, SIX_CLASS_ASSET, 6, null);

        ImageQualityGate qualityGate = new ImageQualityGate();
        InferenceDecisionPolicy policy =
                new InferenceDecisionPolicy(InferencePolicyConfig.createWithCropDomainGate());
        harness = new ResearchInferenceHarness(qualityGate, policy, domainExecutor, diseaseExecutor);

        reusableByteBuffer = ByteBuffer.allocateDirect(ModelConfig.BYTE_BUFFER_SIZE);
    }

    @After
    public void tearDown() {
        if (domainExecutor != null) {
            domainExecutor.close();
        }
        if (diseaseExecutor != null) {
            diseaseExecutor.close();
        }
    }

    @Test
    public void onDevicePipeline_nonLeafSoilInput_rejectedAtFoliarGate() {
        // Create 224x224 non-leaf brown soil bitmap (RGB: 90, 65, 45)
        Bitmap soilBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
        int soilPixel = (0xFF << 24) | (90 << 16) | (65 << 8) | 45;
        int[] pixels = new int[224 * 224];
        Arrays.fill(pixels, soilPixel);
        soilBitmap.setPixels(pixels, 0, 224, 0, 0, 224, 224);

        reusableByteBuffer.rewind();
        ResearchHarnessOutcome outcome = harness.processImage(soilBitmap, reusableByteBuffer);

        assertFalse("Non-leaf soil must be rejected", outcome.isAccepted());
        assertEquals("Must terminate at FOLIAR_GATE stage",
                ResearchHarnessOutcome.Stage.FOLIAR_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                outcome.getDecisionResult().getRejectionDetail());
        assertNull("No disease class can be predicted for non-leaf", outcome.getPredictedClassId());
    }

    @Test
    public void onDevicePipeline_greenFoliageInput_executesRealNativeModels() {
        assertTrue(domainExecutor.isAvailable());
        assertTrue(diseaseExecutor.isAvailable());

        // Create 224x224 green foliage bitmap (RGB: 35, 155, 35)
        Bitmap leafBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
        int greenPixel = (0xFF << 24) | (35 << 16) | (155 << 8) | 35;
        int[] pixels = new int[224 * 224];
        Arrays.fill(pixels, greenPixel);
        leafBitmap.setPixels(pixels, 0, 224, 0, 0, 224, 224);

        reusableByteBuffer.rewind();
        ResearchHarnessOutcome outcome = harness.processImage(leafBitmap, reusableByteBuffer);

        assertNotNull("Pipeline outcome must not be null", outcome);
        assertNotNull("Decision result must not be null", outcome.getDecisionResult());
        // Foliar ratio passed, so terminal stage must be either CROP_DOMAIN_GATE or DECISION_POLICY
        assertTrue("Must proceed past FOLIAR_GATE",
                outcome.getTerminalStage() == ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE
                || outcome.getTerminalStage() == ResearchHarnessOutcome.Stage.DECISION_POLICY);
    }

    @Test
    public void onDeviceTaxonomyMapping_allSixIndicesMapToGlobalIndices() {
        List<SixClassResearchMapping.ClassEntry> entries = SixClassResearchMapping.getEntries();
        assertEquals(6, entries.size());

        assertEquals(0, entries.get(0).localIndex);
        assertEquals(0, entries.get(0).globalIndex);
        assertEquals("rice_bacterial_leaf_blight", entries.get(0).classId);
        assertEquals("rice", entries.get(0).crop);

        assertEquals(1, entries.get(1).localIndex);
        assertEquals(1, entries.get(1).globalIndex);
        assertEquals("rice_brown_spot", entries.get(1).classId);
        assertEquals("rice", entries.get(1).crop);

        assertEquals(2, entries.get(2).localIndex);
        assertEquals(2, entries.get(2).globalIndex);
        assertEquals("rice_leaf_blast", entries.get(2).classId);
        assertEquals("rice", entries.get(2).crop);

        assertEquals(3, entries.get(3).localIndex);
        assertEquals(3, entries.get(3).globalIndex);
        assertEquals("rice_healthy", entries.get(3).classId);
        assertEquals("rice", entries.get(3).crop);

        assertEquals(4, entries.get(4).localIndex);
        assertEquals(16, entries.get(4).globalIndex);
        assertEquals("groundnut_early_leaf_spot", entries.get(4).classId);
        assertEquals("groundnut", entries.get(4).crop);

        assertEquals(5, entries.get(5).localIndex);
        assertEquals(17, entries.get(5).globalIndex);
        assertEquals("groundnut_healthy", entries.get(5).classId);
        assertEquals("groundnut", entries.get(5).crop);
    }

    @Test
    public void onDeviceSafetyGuard_nonDiagnosticOutcomePreventsDiagnosisAndAdvice() {
        Bitmap soilBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
        int nonLeaf = (0xFF << 24) | (100 << 16) | (80 << 8) | 60;
        int[] pixels = new int[224 * 224];
        Arrays.fill(pixels, nonLeaf);
        soilBitmap.setPixels(pixels, 0, 224, 0, 0, 224, 224);

        reusableByteBuffer.rewind();
        ResearchHarnessOutcome outcome = harness.processImage(soilBitmap, reusableByteBuffer);

        assertFalse(outcome.isAccepted());
        assertFalse(outcome.getDecisionResult().isDiagnostic());

        try {
            outcome.getDecisionResult().requireDiseaseClassId();
            org.junit.Assert.fail("requireDiseaseClassId must throw for non-diagnostic outcome");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        try {
            outcome.getDecisionResult().requireAgronomicAdviceClassId();
            org.junit.Assert.fail("requireAgronomicAdviceClassId must throw for non-diagnostic outcome");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
