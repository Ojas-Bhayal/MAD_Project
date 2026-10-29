package com.cropdoc.app.data.repository;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.cropdoc.app.data.local.dao.ScanDao;
import com.cropdoc.app.data.local.entity.ScanEntity;
import com.cropdoc.app.data.remote.FirebaseAuthSource;
import com.cropdoc.app.data.remote.FirestoreSyncSource;
import com.cropdoc.app.data.sync.ScanSyncWorker;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.util.ExifPrivacySanitizer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Offline-first repository storing scans in Room (0 Firestore reads on cached screens)
 * and syncing compact Base64 thumbnails + metadata to Firestore on the free Spark tier.
 */
public class ScanRepository {

    public static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final String SYNC_WORK_NAME = "cropdoc_scan_sync_work";
    private static final int SYNC_BATCH_SIZE = 10;
    private static final String CLOUD_HISTORY_TAG = "CropDocCloudHistory";

    // One background executor avoids network/database work on the UI thread.
    private final ExecutorService cloudHistoryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cropdoc-cloud-history-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> cloudRefreshInFlight = ConcurrentHashMap.newKeySet();

    public interface CloudHistoryRefreshCallback {
        void onFinished(int rawDocuments, int parsedRecords, int importedRecords,
                        @Nullable String error);
    }

    @NonNull
    private final Context appContext;

    @NonNull
    private final ScanDao scanDao;

    @NonNull
    private final FirestoreSyncSource firestoreSource;

    public ScanRepository(
            @NonNull Context context,
            @NonNull ScanDao scanDao,
            @NonNull FirestoreSyncSource firestoreSource) {
        this.appContext = context.getApplicationContext();
        this.scanDao = Objects.requireNonNull(scanDao, "scanDao");
        this.firestoreSource = Objects.requireNonNull(firestoreSource, "firestoreSource");
    }

    @NonNull
    public ScanRecord saveScanSync(
            @NonNull String userId,
            @NonNull DiagnosisResult diagnosis,
            @NonNull String localImagePath) {
        String scanId = UUID.randomUUID().toString();
        long nowMs = System.currentTimeMillis();
        ScanEntity entity = new ScanEntity(
                scanId,
                userId,
                diagnosis.getCropId(),
                diagnosis.getLabelId(),
                diagnosis.getConfidence(),
                diagnosis.getOutcome().name(),
                localImagePath,
                null,
                nowMs,
                false,
                null,
                diagnosis.getModelVersion());
        scanDao.insertOrReplace(entity);
        if (!FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) {
            enqueueBackgroundSync();
        }
        return toDomain(entity);
    }

    @NonNull
    public LiveData<List<ScanRecord>> observeRecentScans(@NonNull String userId) {
        return Transformations.map(
                scanDao.observeRecentScans(userId, DEFAULT_HISTORY_LIMIT),
                entities -> {
                    List<ScanRecord> records = new ArrayList<>(entities.size());
                    for (ScanEntity entity : entities) {
                        records.add(toDomain(entity));
                    }
                    return records;
                });
    }

    /**
     * Imports the signed-in user's most recent cloud scans into Room. Cloud errors are returned
     * to the ViewModel as well as logged; they are no longer indistinguishable from empty history.
     */
    public void refreshCloudHistoryAsync(@NonNull String userId) {
        refreshCloudHistoryAsync(userId, null);
    }

    public void refreshCloudHistoryAsync(
            @NonNull String userId, @Nullable CloudHistoryRefreshCallback callback) {
        if (userId.isEmpty() || FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) {
            Log.w(CLOUD_HISTORY_TAG, "Cloud history refresh skipped: empty/guest UID.");
            if (callback != null) callback.onFinished(0, 0, 0, "No signed-in account.");
            return;
        }
        if (!cloudRefreshInFlight.add(userId)) {
            Log.i(CLOUD_HISTORY_TAG, "Cloud history refresh already in flight for UID suffix="
                    + uidSuffix(userId));
            return; // coalesce duplicate onViewCreated/onResume refresh requests
        }
        Log.i(CLOUD_HISTORY_TAG, "Cloud history refresh queued for UID suffix=" + uidSuffix(userId));
        cloudHistoryExecutor.execute(() -> {
            int rawCount = 0;
            int parsedCount = 0;
            int importedCount = 0;
            String error = null;
            try {
                com.cropdoc.app.data.remote.FirestoreSyncSource.ScanFetchResult fetch =
                        firestoreSource.fetchPaginatedScans(userId, null);
                rawCount = fetch.rawDocumentCount;
                parsedCount = fetch.records.size();
                if (fetch.error != null) {
                    error = fetch.error;
                    Log.e(CLOUD_HISTORY_TAG, "Cloud refresh read error: " + error);
                } else {
                    List<ScanEntity> missingLocalRecords = new ArrayList<>();
                    for (ScanEntity cloudRecord : fetch.records) {
                        if (!userId.equals(cloudRecord.getUserId())) {
                            Log.w(CLOUD_HISTORY_TAG, "Skipping record with unexpected owner.");
                            continue;
                        }
                        ScanEntity localRecord = scanDao.findByIdSync(cloudRecord.getScanId());
                        if (localRecord == null) {
                            missingLocalRecords.add(cloudRecord);
                        }
                    }
                    if (!missingLocalRecords.isEmpty()) {
                        scanDao.insertAll(missingLocalRecords);
                    }
                    importedCount = missingLocalRecords.size();
                    Log.i(CLOUD_HISTORY_TAG, "Cloud history refresh finished. raw=" + rawCount
                            + ", parsed=" + parsedCount + ", imported=" + importedCount
                            + ", skippedMalformed=" + fetch.skippedDocumentCount
                            + ", uidSuffix=" + uidSuffix(userId));
                    if (rawCount == 0) {
                        Log.w(CLOUD_HISTORY_TAG, "Firestore returned zero documents for this UID.");
                    } else if (parsedCount == 0) {
                        error = "Firestore returned documents, but none matched a supported scan schema.";
                    }
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.e(CLOUD_HISTORY_TAG, "Cloud history refresh failed.", e);
            } finally {
                cloudRefreshInFlight.remove(userId);
                if (callback != null) {
                    callback.onFinished(rawCount, parsedCount, importedCount, error);
                }
            }
        });
    }

    @NonNull
    private static String uidSuffix(@Nullable String uid) {
        if (uid == null || uid.isEmpty()) return "<none>";
        return uid.length() <= 6 ? uid : uid.substring(uid.length() - 6);
    }

    @Nullable
    public ScanRecord getScanByIdSync(@NonNull String scanId) {
        ScanEntity entity = scanDao.findByIdSync(scanId);
        return entity != null ? toDomain(entity) : null;
    }

    public void recordFeedbackSync(@NonNull String scanId, boolean helpful) {
        scanDao.updateFeedback(scanId, helpful);
        enqueueBackgroundSync();
    }

    public void deleteAllUserScansSync(@NonNull String userId) {
        List<ScanEntity> existing = scanDao.findAllForUserSync(userId);
        if (existing != null) {
            for (ScanEntity item : existing) {
                if (!item.getLocalImagePath().isEmpty()) {
                    File localFile = new File(item.getLocalImagePath());
                    if (localFile.exists()) {
                        localFile.delete();
                    }
                }
            }
        }
        scanDao.deleteAllForUser(userId);
        firestoreSource.deleteAllUserCloudData(userId);
    }

    public boolean syncPendingBatch(@NonNull String userId) {
        if (FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) {
            return true;
        }
        List<ScanEntity> pending = scanDao.findUnsyncedBatch(userId, SYNC_BATCH_SIZE);
        if (pending.isEmpty()) {
            return true;
        }
        List<ScanEntity> readyForFirestore = new ArrayList<>(pending.size());
        for (ScanEntity item : pending) {
            String remoteThumb = item.getRemoteImageUrl();
            if (remoteThumb == null && !item.getLocalImagePath().isEmpty()) {
                remoteThumb = ExifPrivacySanitizer.encodeCompactBase64Thumbnail(
                        new File(item.getLocalImagePath()));
            }
            readyForFirestore.add(new ScanEntity(
                    item.getScanId(),
                    userId,
                    item.getCropId(),
                    item.getDiseaseId(),
                    item.getConfidence(),
                    item.getOutcome(),
                    item.getLocalImagePath(),
                    remoteThumb,
                    item.getTimestampMs(),
                    true,
                    item.getUserFeedbackHelpful(),
                    item.getModelVersion()));
        }
        boolean committed = firestoreSource.uploadScanBatch(userId, readyForFirestore);
        if (committed) {
            for (ScanEntity syncedItem : readyForFirestore) {
                scanDao.markSynced(syncedItem.getScanId(), syncedItem.getRemoteImageUrl());
            }
        }
        return committed;
    }

    private void enqueueBackgroundSync() {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ScanSyncWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(appContext)
                    .enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, request);
        } catch (Throwable ignored) {
            // WorkManager may not be initialized during pure JVM unit tests
        }
    }

    @NonNull
    private static ScanRecord toDomain(@NonNull ScanEntity entity) {
        DiagnosisOutcome outcome;
        try {
            outcome = DiagnosisOutcome.valueOf(entity.getOutcome());
        } catch (IllegalArgumentException e) {
            outcome = DiagnosisOutcome.CONFIDENT_DISEASE;
        }
        return new ScanRecord(
                entity.getScanId(),
                entity.getUserId(),
                entity.getCropId(),
                entity.getDiseaseId(),
                entity.getConfidence(),
                outcome,
                entity.getLocalImagePath(),
                entity.getRemoteImageUrl(),
                entity.getTimestampMs(),
                entity.isSynced(),
                entity.getUserFeedbackHelpful(),
                entity.getModelVersion());
    }
}
