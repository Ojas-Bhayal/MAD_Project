package com.cropdoc.app.data.sync;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.di.AppContainer;

/**
 * WorkManager 2.12.0 background worker that syncs pending Room scan records and feedback
 * to Cloud Storage and Firestore when network connectivity is available.
 */
public final class ScanSyncWorker extends Worker {

    public ScanSyncWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context appContext = getApplicationContext();
        if (!(appContext instanceof CropDocApplication)) {
            return Result.failure();
        }
        AppContainer container = ((CropDocApplication) appContext).getAppContainer();
        String userId = container.getAuthRepository().getCurrentUserId();
        boolean ok = container.getScanRepository().syncPendingBatch(userId);
        return ok ? Result.success() : Result.retry();
    }
}
