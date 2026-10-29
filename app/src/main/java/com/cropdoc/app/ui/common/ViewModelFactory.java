package com.cropdoc.app.ui.common;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import com.cropdoc.app.ui.auth.AuthViewModel;
import com.cropdoc.app.ui.history.HistoryViewModel;
import com.cropdoc.app.ui.result.ResultViewModel;
import com.cropdoc.app.ui.scan.ScanViewModel;
import java.util.Objects;

/**
 * Centralized ViewModelProvider.Factory constructing ViewModels via explicit constructor DI.
 */
public final class ViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    private final AuthRepository authRepository;

    @NonNull
    private final ScanRepository scanRepository;

    @NonNull
    private final AdviceRepository adviceRepository;

    @NonNull
    private final AnalyzeLeafUseCase analyzeLeafUseCase;

    @NonNull
    private final AppExecutors executors;

    public ViewModelFactory(
            @NonNull AuthRepository authRepository,
            @NonNull ScanRepository scanRepository,
            @NonNull AdviceRepository adviceRepository,
            @NonNull AnalyzeLeafUseCase analyzeLeafUseCase,
            @NonNull AppExecutors executors) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.adviceRepository = Objects.requireNonNull(adviceRepository, "adviceRepository");
        this.analyzeLeafUseCase = Objects.requireNonNull(
                analyzeLeafUseCase, "analyzeLeafUseCase");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(AuthViewModel.class)) {
            return (T) new AuthViewModel(authRepository, scanRepository, executors);
        }
        if (modelClass.isAssignableFrom(ScanViewModel.class)) {
            return (T) new ScanViewModel(analyzeLeafUseCase, executors);
        }
        if (modelClass.isAssignableFrom(ResultViewModel.class)) {
            return (T) new ResultViewModel(scanRepository, adviceRepository, executors);
        }
        if (modelClass.isAssignableFrom(HistoryViewModel.class)) {
            return (T) new HistoryViewModel(scanRepository, authRepository, executors);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
