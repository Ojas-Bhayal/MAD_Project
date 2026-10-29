package com.cropdoc.app.ml.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tensorflow.lite.DataType;

/**
 * Android instrumentation tests executing the actual LiteRT native interpreter on-device/emulator.
 * Validates asset loading, tensor shapes, finite output values, and on-device execution latency.
 */
@RunWith(AndroidJUnit4.class)
public final class LiteRtOnDeviceInferenceTest {

    private static final String DOMAIN_GATE_ASSET =
            "research_models/crop_domain_gate_fp32.tflite";
    private static final String SIX_CLASS_ASSET =
            "research_models/unified_six_class_fp32.tflite";

    private Context context;
    private LiteRtResearchModelExecutor domainExecutor;
    private LiteRtResearchModelExecutor diseaseExecutor;
    private ByteBuffer nchwInputBuffer;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        domainExecutor = LiteRtResearchModelExecutor.fromAsset(context, DOMAIN_GATE_ASSET, 3, null);
        diseaseExecutor = LiteRtResearchModelExecutor.fromAsset(context, SIX_CLASS_ASSET, 6, null);

        nchwInputBuffer = ByteBuffer.allocateDirect(LiteRtResearchModelExecutor.EXPECTED_NCHW_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 3 * 224 * 224; i++) {
            nchwInputBuffer.putFloat(0.1f);
        }
        nchwInputBuffer.rewind();
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
    public void cropDomainGate_loadsOnDeviceAndMatchesTensorContract() {
        assertTrue("Crop-domain gate must load without UnsatisfiedLinkError: "
                + domainExecutor.getLoadErrorMessage(), domainExecutor.isAvailable());

        int[] inShape = domainExecutor.getInputTensorShape();
        assertNotNull("Domain gate input tensor shape must not be null", inShape);
        assertEquals(4, inShape.length);
        assertEquals(1, inShape[0]);
        assertEquals(3, inShape[1]);
        assertEquals(224, inShape[2]);
        assertEquals(224, inShape[3]);
        assertEquals(DataType.FLOAT32, domainExecutor.getInputTensorDataType());

        int[] outShape = domainExecutor.getOutputTensorShape();
        assertNotNull("Domain gate output tensor shape must not be null", outShape);
        assertEquals(2, outShape.length);
        assertEquals(1, outShape[0]);
        assertEquals(3, outShape[1]);
        assertEquals(DataType.FLOAT32, domainExecutor.getOutputTensorDataType());
    }

    @Test
    public void unifiedSixClassModel_loadsOnDeviceAndMatchesTensorContract() {
        assertTrue("Unified 6-class model must load without UnsatisfiedLinkError: "
                + diseaseExecutor.getLoadErrorMessage(), diseaseExecutor.isAvailable());

        int[] inShape = diseaseExecutor.getInputTensorShape();
        assertNotNull("6-class model input tensor shape must not be null", inShape);
        assertEquals(4, inShape.length);
        assertEquals(1, inShape[0]);
        assertEquals(3, inShape[1]);
        assertEquals(224, inShape[2]);
        assertEquals(224, inShape[3]);
        assertEquals(DataType.FLOAT32, diseaseExecutor.getInputTensorDataType());

        int[] outShape = diseaseExecutor.getOutputTensorShape();
        assertNotNull("6-class model output tensor shape must not be null", outShape);
        assertEquals(2, outShape.length);
        assertEquals(1, outShape[0]);
        assertEquals(6, outShape[1]);
        assertEquals(DataType.FLOAT32, diseaseExecutor.getOutputTensorDataType());
    }

    @Test
    public void actualOnDeviceInference_executesAndReturnsFiniteLogits() throws Exception {
        assertTrue(domainExecutor.isAvailable());
        assertTrue(diseaseExecutor.isAvailable());

        nchwInputBuffer.rewind();
        float[] domainLogits = domainExecutor.execute(nchwInputBuffer);
        assertEquals(3, domainLogits.length);
        assertFinite(domainLogits);
        assertTrue(domainExecutor.getLastExecutionLatencyMs() >= 0L);

        nchwInputBuffer.rewind();
        float[] diseaseLogits = diseaseExecutor.execute(nchwInputBuffer);
        assertEquals(6, diseaseLogits.length);
        assertFinite(diseaseLogits);
        assertTrue(diseaseExecutor.getLastExecutionLatencyMs() >= 0L);
    }

    @Test
    public void onDeviceInferenceLatencyBenchmark_measuredAcrossMultipleIterations() throws Exception {
        assertTrue(domainExecutor.isAvailable());
        assertTrue(diseaseExecutor.isAvailable());

        int iterations = 15;
        long domainTotalMs = 0L;
        long diseaseTotalMs = 0L;

        // Warm-up run
        nchwInputBuffer.rewind();
        domainExecutor.execute(nchwInputBuffer);
        nchwInputBuffer.rewind();
        diseaseExecutor.execute(nchwInputBuffer);

        for (int i = 0; i < iterations; i++) {
            nchwInputBuffer.rewind();
            long t0 = System.nanoTime();
            domainExecutor.execute(nchwInputBuffer);
            domainTotalMs += (System.nanoTime() - t0) / 1_000_000L;

            nchwInputBuffer.rewind();
            long t1 = System.nanoTime();
            diseaseExecutor.execute(nchwInputBuffer);
            diseaseTotalMs += (System.nanoTime() - t1) / 1_000_000L;
        }

        double avgDomainMs = (double) domainTotalMs / iterations;
        double avgDiseaseMs = (double) diseaseTotalMs / iterations;

        android.util.Log.i("LiteRtBenchmark", String.format(
                "Device LiteRT Latency (%d runs) - DomainGate: %.2f ms, 6ClassModel: %.2f ms",
                iterations, avgDomainMs, avgDiseaseMs));

        assertTrue("Domain gate latency must be recorded", avgDomainMs >= 0.0);
        assertTrue("Disease model latency must be recorded", avgDiseaseMs >= 0.0);
    }

    private static void assertFinite(float[] array) {
        for (float val : array) {
            assertFalse("Output array must not contain NaN", Float.isNaN(val));
            assertFalse("Output array must not contain Infinite", Float.isInfinite(val));
        }
    }
}
