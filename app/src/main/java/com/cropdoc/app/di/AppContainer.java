package com.cropdoc.app.di;

import android.content.Context;
import androidx.annotation.NonNull;
import com.cropdoc.app.data.local.CropDocDatabase;
import com.cropdoc.app.data.remote.FirebaseAuthSource;
import com.cropdoc.app.data.remote.FirestoreSyncSource;
import com.cropdoc.app.data.remote.ModelUpdateManager;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import com.cropdoc.app.ml.LeafClassifier;
import com.cropdoc.app.ml.LiteRtLeafClassifier;
import com.cropdoc.app.ui.common.ViewModelFactory;

/**
 * Manual Dependency Injection container providing lazy, thread-safe singletons with zero
 * reflection or annotation-processing startup penalty.
 */
public final class AppContainer {

    @NonNull
    private final Context appContext;

    @NonNull
    private final AppExecutors executors;

    private volatile CropDocDatabase database;
    private volatile AuthRepository authRepository;
    private volatile AdviceRepository adviceRepository;
    private volatile ScanRepository scanRepository;
    private volatile ModelUpdateManager modelUpdateManager;
    private volatile LeafClassifier leafClassifier;
    private volatile AnalyzeLeafUseCase analyzeLeafUseCase;
    private volatile ViewModelFactory viewModelFactory;

    public AppContainer(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.executors = AppExecutors.createDefault();
    }

    @NonNull
    public AppExecutors getExecutors() {
        return executors;
    }

    @NonNull
    public synchronized CropDocDatabase getDatabase() {
        if (database == null) {
            database = CropDocDatabase.getInstance(appContext);
        }
        return database;
    }

    @NonNull
    public synchronized AuthRepository getAuthRepository() {
        if (authRepository == null) {
            authRepository = new AuthRepository(new FirebaseAuthSource());
        }
        return authRepository;
    }

    @NonNull
    public synchronized AdviceRepository getAdviceRepository() {
        if (adviceRepository == null) {
            adviceRepository = new AdviceRepository(appContext, getDatabase().adviceDao());
        }
        return adviceRepository;
    }

    @NonNull
    public synchronized ScanRepository getScanRepository() {
        if (scanRepository == null) {
            scanRepository = new ScanRepository(
                    appContext,
                    getDatabase().scanDao(),
                    new FirestoreSyncSource());
        }
        return scanRepository;
    }

    @NonNull
    public synchronized ModelUpdateManager getModelUpdateManager() {
        if (modelUpdateManager == null) {
            modelUpdateManager = new ModelUpdateManager(appContext);
        }
        return modelUpdateManager;
    }

    @NonNull
    public synchronized LeafClassifier getLeafClassifier() {
        if (leafClassifier == null) {
            leafClassifier = new LiteRtLeafClassifier(appContext, getModelUpdateManager());
        }
        return leafClassifier;
    }

    @NonNull
    public synchronized AnalyzeLeafUseCase getAnalyzeLeafUseCase() {
        if (analyzeLeafUseCase == null) {
            analyzeLeafUseCase = new AnalyzeLeafUseCase(
                    getLeafClassifier(),
                    getAdviceRepository(),
                    getScanRepository(),
                    getAuthRepository());
        }
        return analyzeLeafUseCase;
    }

    @NonNull
    public synchronized ViewModelFactory getViewModelFactory() {
        if (viewModelFactory == null) {
            viewModelFactory = new ViewModelFactory(
                    getAuthRepository(),
                    getScanRepository(),
                    getAdviceRepository(),
                    getAnalyzeLeafUseCase(),
                    getExecutors());
        }
        return viewModelFactory;
    }
}
