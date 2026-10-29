package com.cropdoc.app.ui.result;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ui.common.Result;
import java.util.Objects;

/**
 * ViewModel loading a scan record, its localized ICAR/CABI advice, and recording user feedback.
 */
public final class ResultViewModel extends ViewModel {

    public static final class ResultScreenState {
        @NonNull
        private final ScanRecord scanRecord;

        @Nullable
        private final CropDiseaseAdvice advice;

        public ResultScreenState(
                @NonNull ScanRecord scanRecord,
                @Nullable CropDiseaseAdvice advice) {
            this.scanRecord = Objects.requireNonNull(scanRecord, "scanRecord");
            this.advice = advice;
        }

        @NonNull
        public ScanRecord getScanRecord() {
            return scanRecord;
        }

        @Nullable
        public CropDiseaseAdvice getAdvice() {
            return advice;
        }

        public boolean shouldShowChemicalAdvice() {
            return scanRecord.getOutcome() == DiagnosisOutcome.CONFIDENT_DISEASE;
        }
    }

    @NonNull
    private final ScanRepository scanRepository;

    @NonNull
    private final AdviceRepository adviceRepository;

    @NonNull
    private final AppExecutors executors;

    private final MutableLiveData<Result<ResultScreenState>> screenState =
            new MutableLiveData<>();

    private final MutableLiveData<Boolean> feedbackSubmitted = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> lastFeedbackHelpful = new MutableLiveData<>();

    public ResultViewModel(
            @NonNull ScanRepository scanRepository,
            @NonNull AdviceRepository adviceRepository,
            @NonNull AppExecutors executors) {
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.adviceRepository = Objects.requireNonNull(adviceRepository, "adviceRepository");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @NonNull
    public LiveData<Result<ResultScreenState>> getScreenState() {
        return screenState;
    }

    @NonNull
    public LiveData<Boolean> getFeedbackSubmitted() {
        return feedbackSubmitted;
    }

    @NonNull
    public LiveData<Boolean> getLastFeedbackHelpful() {
        return lastFeedbackHelpful;
    }

    public void loadScanResult(@NonNull String scanId, @NonNull String languageCode) {
        screenState.setValue(Result.loading());
        executors.diskIo().execute(() -> {
            try {
                ScanRecord record = scanRepository.getScanByIdSync(scanId);
                if (record == null) {
                    screenState.postValue(Result.error("SCAN_NOT_FOUND"));
                    return;
                }
                if (record.getUserFeedbackHelpful() != null) {
                    lastFeedbackHelpful.postValue(record.getUserFeedbackHelpful());
                    feedbackSubmitted.postValue(true);
                }
                CropDiseaseAdvice advice = null;
                if (record.getOutcome() == DiagnosisOutcome.CONFIDENT_DISEASE
                        || record.getOutcome() == DiagnosisOutcome.HEALTHY_LEAF) {
                    advice = adviceRepository.getAdviceSync(record.getDiseaseId(), languageCode);
                }
                screenState.postValue(Result.success(new ResultScreenState(record, advice)));
            } catch (Throwable e) {
                screenState.postValue(Result.error("SCAN_LOAD_ERROR"));
            }
        });
    }

    public void submitFeedback(@NonNull String scanId, boolean helpful) {
        Boolean previous = lastFeedbackHelpful.getValue();
        if (previous != null && previous.booleanValue() == helpful) {
            return;
        }
        lastFeedbackHelpful.setValue(helpful);
        feedbackSubmitted.setValue(true);
        executors.diskIo().execute(() -> {
            try {
                scanRepository.recordFeedbackSync(scanId, helpful);
            } catch (Throwable ignored) {
                // Safe background feedback persistence
            }
        });
    }
}
