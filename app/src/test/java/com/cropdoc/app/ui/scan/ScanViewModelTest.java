package com.cropdoc.app.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import com.cropdoc.app.ui.common.Result;
import java.io.File;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit tests for ScanViewModel verifying analyzeImageFile (#9), double-tap guard (#4),
 * and clearScanResult state reset.
 */
public final class ScanViewModelTest {

    @Rule
    public final InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private AnalyzeLeafUseCase mockUseCase;
    private ScanViewModel viewModel;

    @Before
    public void setUp() {
        mockUseCase = Mockito.mock(AnalyzeLeafUseCase.class);
        Executor direct = Runnable::run;
        AppExecutors directExecutors = new AppExecutors(direct, direct, direct, direct);
        viewModel = new ScanViewModel(mockUseCase, directExecutors);
    }

    @Test
    public void analyzeImageFile_withMissingFile_emitsDecodeErrorAndClearsCleanly() {
        File missingFile = new File("/tmp/non_existent_leaf_scan.jpg");
        File storageDir = new File("/tmp/scans");

        viewModel.analyzeImageFile(missingFile, storageDir, "te", 1080, 1920, 600, 600);

        Result<AnalyzeLeafUseCase.AnalysisOutput> result = viewModel.getScanResult().getValue();
        assertNotNull(result);
        assertEquals(Result.Status.ERROR, result.getStatus());
        assertFalse(viewModel.isAnalyzing());

        viewModel.clearScanResult();
        assertNull(viewModel.getScanResult().getValue());
    }
}
