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
import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModelProvider;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ml.policy.SixClassResearchMapping;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit tests verifying ResearchDemoViewModel state transitions, fail-closed handling,
 * request isolation, and isolated research taxonomy mappings.
 */
public final class ResearchDemoViewModelTest {

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
        mockCacheDir = new File(System.getProperty("java.io.tmpdir"), "cropdoc_test_cache");
        mockCacheDir.mkdirs();

        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getAssets()).thenReturn(mockAssets);
        when(mockContext.getCacheDir()).thenReturn(mockCacheDir);
        when(mockAssets.open(Mockito.anyString()))
                .thenThrow(new IOException("Simulated asset exception for host JVM"));

        // Setup foliage bitmap mock: green pixels (RGB 40, 160, 40)
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

        // Setup soil bitmap mock: brown pixels (RGB 136, 112, 89)
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
    public void initialConstruction_setsNoImageSelectedState() {
        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.NO_IMAGE_SELECTED, state.getStatus());
        assertFalse(state.isModelsAvailable());
        assertNotNull(state.getMessage());
        assertNull(state.getPreviewBitmap());
        assertNull(state.getOutcome());
    }

    @Test
    public void analyzeBitmap_withSoilInput_triggersFoliarGateRejectionOutOfDomain() {
        viewModel.analyzeBitmap(mockSoilBitmap);

        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.OUT_OF_DOMAIN, state.getStatus());
        assertNotNull(state.getPreviewBitmap());
        assertNotNull(state.getOutcome());

        ResearchHarnessOutcome outcome = state.getOutcome();
        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.FOLIAR_GATE, outcome.getTerminalStage());
        assertNull(outcome.getPredictedClassId());
        assertTrue(outcome.getExplanation().contains("foliar"));
        assertEquals(-1L, state.getDomainLatencyMs());
        assertEquals(-1L, state.getDiseaseLatencyMs());
    }

    @Test
    public void analyzeBitmap_withFoliageAndUnavailableModels_failsClosedToInconclusive() {
        viewModel.analyzeBitmap(mockFoliageBitmap);

        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.INCONCLUSIVE, state.getStatus());
        assertNotNull(state.getOutcome());

        ResearchHarnessOutcome outcome = state.getOutcome();
        assertFalse(outcome.isAccepted());
        assertEquals(ResearchHarnessOutcome.Stage.CROP_DOMAIN_GATE, outcome.getTerminalStage());
    }

    @Test
    public void analyzeUri_withUnreadableStream_transitionsToErrorState() {
        ContentResolver mockResolver = mock(ContentResolver.class);
        Uri mockUri = mock(Uri.class);

        viewModel.analyzeUri(mockResolver, mockUri);

        ResearchDemoState state = viewModel.getState().getValue();
        assertNotNull(state);
        assertEquals(ResearchDemoState.Status.ERROR, state.getStatus());
        assertNotNull(state.getMessage());
        assertNull(state.getOutcome());
    }

    @Test
    public void researchTaxonomyMapping_preservesStrictSixClassSpecification() {
        List<SixClassResearchMapping.ClassEntry> entries = SixClassResearchMapping.getEntries();
        assertEquals(6, entries.size());

        // Rice entries (Global 0-3)
        assertEquals(0, entries.get(0).localIndex);
        assertEquals(0, entries.get(0).globalIndex);
        assertEquals("rice_bacterial_leaf_blight", entries.get(0).classId);

        assertEquals(1, entries.get(1).localIndex);
        assertEquals(1, entries.get(1).globalIndex);
        assertEquals("rice_brown_spot", entries.get(1).classId);

        assertEquals(2, entries.get(2).localIndex);
        assertEquals(2, entries.get(2).globalIndex);
        assertEquals("rice_leaf_blast", entries.get(2).classId);

        assertEquals(3, entries.get(3).localIndex);
        assertEquals(3, entries.get(3).globalIndex);
        assertEquals("rice_healthy", entries.get(3).classId);

        // Groundnut entries (Global 16-17)
        assertEquals(4, entries.get(4).localIndex);
        assertEquals(16, entries.get(4).globalIndex);
        assertEquals("groundnut_early_leaf_spot", entries.get(4).classId);

        assertEquals(5, entries.get(5).localIndex);
        assertEquals(17, entries.get(5).globalIndex);
        assertEquals("groundnut_healthy", entries.get(5).classId);
    }

    @Test
    public void factoryCreation_instantiatesValidViewModelInstance() {
        ViewModelProvider.Factory factory =
                ResearchDemoViewModel.provideFactory(mockContext, directExecutors);
        ResearchDemoViewModel instance = factory.create(ResearchDemoViewModel.class);
        assertNotNull(instance);
        assertNotNull(instance.getState().getValue());
    }

    @Test
    public void stateRecord_errorFactoryConstructsValidErrorState() {
        Bitmap dummy = mock(Bitmap.class);
        ResearchDemoState err = ResearchDemoState.error(dummy, "Decode failed", false);
        assertEquals(ResearchDemoState.Status.ERROR, err.getStatus());
        assertEquals("Decode failed", err.getMessage());
        assertEquals(dummy, err.getPreviewBitmap());
        assertFalse(err.isModelsAvailable());
    }

    @Test
    public void stateRecord_imageReadyFactoryConstructsValidState() {
        Bitmap dummy = mock(Bitmap.class);
        ResearchDemoState ready = ResearchDemoState.imageReady(dummy, true);
        assertEquals(ResearchDemoState.Status.IMAGE_READY, ready.getStatus());
        assertEquals(dummy, ready.getPreviewBitmap());
        assertNull(ready.getOutcome());
        assertTrue(ready.isModelsAvailable());
    }
}
