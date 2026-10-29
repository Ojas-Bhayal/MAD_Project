package com.cropdoc.app.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.cropdoc.app.data.local.entity.ScanEntity;
import java.util.List;

/**
 * Data Access Object for local scan records with user-scoped queries and bounded pagination.
 */
@Dao
public interface ScanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrReplace(@NonNull ScanEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(@NonNull List<ScanEntity> entities);

    @NonNull
    @Query("SELECT * FROM scans WHERE user_id = :userId ORDER BY timestamp_ms DESC LIMIT :limit")
    LiveData<List<ScanEntity>> observeRecentScans(@NonNull String userId, int limit);

    @NonNull
    @Query("SELECT * FROM scans WHERE user_id = :userId")
    List<ScanEntity> findAllForUserSync(@NonNull String userId);

    @Nullable
    @Query("SELECT * FROM scans WHERE scan_id = :scanId LIMIT 1")
    ScanEntity findByIdSync(@NonNull String scanId);

    @NonNull
    @Query("SELECT * FROM scans WHERE user_id = :userId AND synced = 0 "
            + "ORDER BY timestamp_ms ASC LIMIT :batchSize")
    List<ScanEntity> findUnsyncedBatch(@NonNull String userId, int batchSize);

    @Query("UPDATE scans SET synced = 1, remote_image_url = :remoteUrl WHERE scan_id = :scanId")
    void markSynced(@NonNull String scanId, @Nullable String remoteUrl);

    @Query("UPDATE scans SET user_feedback_helpful = :helpful, synced = 0 WHERE scan_id = :scanId")
    void updateFeedback(@NonNull String scanId, boolean helpful);

    @Query("DELETE FROM scans WHERE user_id = :userId")
    void deleteAllForUser(@NonNull String userId);
}
