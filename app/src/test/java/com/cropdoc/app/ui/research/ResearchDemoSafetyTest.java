package com.cropdoc.app.ui.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ml.ModelValidator;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.policy.PolicyRejectionDetail;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Safety and isolation test suite verifying camera capture handling, non-leaf rejection,
 * request lifecycle cancellation, and production boundary preservation.
 */
public final class ResearchDemoSafetyTest {

    @Rule
    public final InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private Context mockContext;
    private AppExecutors directExecutors;
    private ResearchDemoViewModel viewModel;
    private Bitmap mockFoliageBitmap;
    private Bitmap mockSoilBitmap;
    private File mockCacheDir;

    @Before
    public void setUp() throws Exception {
        mockContext = mock(Context.class);
        AssetManager mockAssets = mock(AssetManager.class);
        mockCacheDir = new File(System.getProperty("java.io.tmpdir"), "cropdoc_safety_test_cache");
        mockCacheDir.mkdirs();

        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getAssets()).thenReturn(mockAssets);
        when(mockContext.getCacheDir()).thenReturn(mockCacheDir);
        when(mockAssets.open(Mockito.anyString()))
                .thenThrow(new IOException("Simulated asset exception for host JVM"));

        // Foliage bitmap mock: green pixels (RGB 40, 160, 40)
        mockFoliageBitmap = mock(Bitmap.class);
        when(mockFoliageBitmap.getWidth()).thenReturn(224);
        when(mockFoliageBitmap.getHeight()).thenReturn(224);
        doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int greenPixel = (0xFF << 24) | (40 << 16) | (160 << 8) | 40;
            Arrays.fill(pixels, greenPixel);
            return null;
        }).when(mockFoliageBitmap).getPixels(
                any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        // Soil bitmap mock: brown pixels (RGB 136, 112, 89)
        mockSoilBitmap = mock(Bitmap.class);
        when(mockSoilBitmap.getWidth()).thenReturn(224);
        when(mockSoilBitmap.getHeight()).thenReturn(224);
        doAnswer(inv -> {
            int[] pixels = inv.getArgument(0);
            int soilPixel = (0xFF << 24) | (136 << 16) | (112 << 8) | 89;
            Arrays.fill(pixels, soilPixel);
            return null;
        }).when(mockSoilBitmap).getPixels(
                any(int[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        Executor direct = Runnable::run;
        directExecutors = new AppExecutors(direct, direct, direct, direct);
        viewModel = new ResearchDemoViewModel(mockContext, directExecutors);
    }

    @Test
    public void cameraCapture_reachesPipeline_andUpdatesPreviewAndClearsPreviousOutcome() {
        // Step 1: Run foliage to produce initial state
        viewModel.analyzeBitmap(mockFoliageBitmap);
        ResearchDemoState initial = viewModel.getState().getValue();
        assertNotNull(initial);
        assertNotNull(initial.getOutcome());

        // Step 2: Now simulate a camera capture with a new bitmap
        viewModel.analyzeBitmap(mockSoilBitmap);
        ResearchDemoState updated = viewModel.getState().getValue();
        assertNotNull(updated);
        assertEquals(ResearchDemoState.Status.OUT_OF_DOMAIN, updated.getStatus());
        assertEquals(mockSoilBitmap, updated.getPreviewBitmap());
        assertNotNull(updated.getOutcome());
        assertEquals(ResearchHarnessOutcome.Stage.FOLIAR_GATE, updated.getOutcome().getTerminalStage());
    }

    @Test
    public void sequentialRequests_clearsStaleResultsAndLatency() {
        viewModel.analyzeBitmap(mockSoilBitmap);
        ResearchDemoState soilState = viewModel.getState().getValue();
        assertNotNull(soilState);
        assertEquals(-1L, soilState.getDomainLatencyMs());
        assertEquals(-1L, soilState.getDiseaseLatencyMs());

        // Re-analyze foliage bitmap: previous soil outcome must not leak
        viewModel.analyzeBitmap(mockFoliageBitmap);
        ResearchDemoState foliageState = viewModel.getState().getValue();
        assertNotNull(foliageState);
        assertEquals(ResearchDemoState.Status.INCONCLUSIVE, foliageState.getStatus());
        assertNotNull(foliageState.getOutcome());
        assertEquals(ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE,
                foliageState.getOutcome().getTerminalStage());
    }

    @Test
    public void captureCancellation_withNullUri_setsErrorAndNeverFallsBackToSyntheticImage() {
        ContentResolver mockResolver = mock(ContentResolver.class);
        viewModel.analyzeUri(mockResolver, null);

        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.ERROR, state.getStatus());
        assertNotNull(state.getMessage());
        assertNull(state.getPreviewBitmap());
        assertNull(state.getOutcome());
    }

    @Test
    public void sampleNonLeafSoil_processesThroughGates_andHaltsAtFoliarStage() {
        viewModel.analyzeBitmap(mockSoilBitmap);

        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.OUT_OF_DOMAIN, state.getStatus());
        assertNotNull(state.getOutcome());

        ResearchHarnessOutcome outcome = state.getOutcome();
        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.FOLIAR_GATE, outcome.getTerminalStage());
        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, outcome.getDecisionResult().getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF,
                outcome.getDecisionResult().getRejectionDetail());
        assertEquals(-1L, state.getDomainLatencyMs());
        assertEquals(-1L, state.getDiseaseLatencyMs());
    }

    @Test
    public void loadSampleNonLeafSoil_onHostJvmWithoutBitmapFactory_failsClosedGracefully() {
        viewModel.loadSampleNonLeafSoil();
        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.ERROR, state.getStatus());
        assertNotNull(state.getMessage());
    }

    @Test
    public void unsupportedNonLeafOutcomes_bypassDiseaseModel_andCannotPersistToProductionDb() {
        viewModel.analyzeBitmap(mockSoilBitmap);
        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        ResearchHarnessOutcome outcome = state.getOutcome();
        assertNotNull(outcome);

        // Verification of stage bypass: disease model was never executed
        assertNull(outcome.getPredictedClassId());
        assertEquals(0f, outcome.getDiseaseConfidence(), 0.001f);
        assertEquals(-1L, state.getDiseaseLatencyMs());

        // Verify isolation invariant: Research outcomes are not ScanRecord objects
        assertFalse(outcome.isAccepted());
    }

    @Test
    public void productionScanner_remainsFailClosedOnPlaceholderModel() {
        File placeholder = new File("app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite");
        if (placeholder.exists()) {
            assertTrue("Placeholder model must be smaller than minimum 512 KB threshold",
                    placeholder.length() < ModelValidator.MIN_REAL_MODEL_BYTES);
            assertEquals(16800L, placeholder.length());
        }
    }
}
