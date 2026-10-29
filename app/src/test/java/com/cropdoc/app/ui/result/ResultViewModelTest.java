package com.cropdoc.app.ui.result;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ui.common.Result;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Unit tests verifying ResultViewModel suppresses chemical advice on low-confidence AND healthy
 * leaf scans, and records user feedback cleanly.
 */
public final class ResultViewModelTest {

    @Rule
    public final InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private ScanRepository mockScanRepo;
    private AdviceRepository mockAdviceRepo;
    private ResultViewModel viewModel;

    @Before
    public void setUp() {
        mockScanRepo = mock(ScanRepository.class);
        mockAdviceRepo = mock(AdviceRepository.class);
        Executor direct = Runnable::run;
        AppExecutors directExecutors = new AppExecutors(direct, direct, direct, direct);
        viewModel = new ResultViewModel(mockScanRepo, mockAdviceRepo, directExecutors);
    }

    @Test
    public void loadScanResult_lowConfidence_hidesChemicalAdvice() {
        ScanRecord lowConfRecord = new ScanRecord(
                "scan_low",
                "guest",
                "tomato",
                "tomato_early_blight",
                0.42f,
                DiagnosisOutcome.LOW_CONFIDENCE_RETAKE,
                "/tmp/low.jpg",
                null,
                1700000000L,
                false,
                null,
                "1.0.0-int8");
        when(mockScanRepo.getScanByIdSync("scan_low")).thenReturn(lowConfRecord);
        viewModel.loadScanResult("scan_low", "en");
        Result<ResultViewModel.ResultScreenState> res = viewModel.getScreenState().getValue();
        assertNotNull(res);
        assertNotNull(res.getData());
        assertFalse(res.getData().shouldShowChemicalAdvice());
        assertNull(res.getData().getAdvice());
    }

    @Test
    public void loadScanResult_healthyLeaf_hidesChemicalAdviceSection() {
        ScanRecord healthyRecord = new ScanRecord(
                "scan_healthy",
                "guest",
                "rice",
                "rice_healthy",
                0.94f,
                DiagnosisOutcome.HEALTHY_LEAF,
                "/tmp/healthy.jpg",
                null,
                1700000000L,
                false,
                null,
                "1.0.0-int8");
        when(mockScanRepo.getScanByIdSync("scan_healthy")).thenReturn(healthyRecord);
        viewModel.loadScanResult("scan_healthy", "en");
        Result<ResultViewModel.ResultScreenState> res = viewModel.getScreenState().getValue();
        assertNotNull(res);
        assertNotNull(res.getData());
        assertFalse(res.getData().shouldShowChemicalAdvice());
    }

    @Test
    public void loadScanResult_confidentDisease_loadsAdviceAndAllowsDisplay() {
        ScanRecord confidentRecord = new ScanRecord(
                "scan_ok",
                "guest",
                "rice",
                "rice_leaf_blast",
                0.89f,
                DiagnosisOutcome.CONFIDENT_DISEASE,
                "/tmp/ok.jpg",
                null,
                1700000000L,
                false,
                null,
                "1.0.0-int8");
        CropDiseaseAdvice advice = new CropDiseaseAdvice(
                "rice_leaf_blast",
                "Rice",
                "Leaf Blast",
                "Magnaporthe oryzae",
                "Pseudomonas fluorescens",
                "Tricyclazole 75% WP",
                "Resistant varieties",
                "ICAR-IIRR");
        when(mockScanRepo.getScanByIdSync("scan_ok")).thenReturn(confidentRecord);
        when(mockAdviceRepo.getAdviceSync("rice_leaf_blast", "en")).thenReturn(advice);
        viewModel.loadScanResult("scan_ok", "en");
        Result<ResultViewModel.ResultScreenState> res = viewModel.getScreenState().getValue();
        assertNotNull(res);
        assertNotNull(res.getData());
        assertTrue(res.getData().shouldShowChemicalAdvice());
        assertNotNull(res.getData().getAdvice());
    }

    @Test
    public void submitFeedback_ignoresIdenticalRepeatAndAllowsCorrection() {
        viewModel.submitFeedback("scan_ok", true);
        viewModel.submitFeedback("scan_ok", true);
        verify(mockScanRepo, times(1)).recordFeedbackSync("scan_ok", true);
        assertEquals(Boolean.TRUE, viewModel.getFeedbackSubmitted().getValue());
        assertEquals(Boolean.TRUE, viewModel.getLastFeedbackHelpful().getValue());

        viewModel.submitFeedback("scan_ok", false);
        verify(mockScanRepo, times(1)).recordFeedbackSync("scan_ok", false);
        assertEquals(Boolean.FALSE, viewModel.getLastFeedbackHelpful().getValue());
    }
}
