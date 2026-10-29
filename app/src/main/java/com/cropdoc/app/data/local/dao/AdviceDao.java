package com.cropdoc.app.data.local.dao;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.cropdoc.app.data.local.entity.AdviceEntity;
import java.util.List;

/**
 * Data Access Object for offline ICAR/CABI/CIB&RC crop disease treatment guidance.
 */
@Dao
public interface AdviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(@NonNull List<AdviceEntity> items);

    @Nullable
    @Query("SELECT * FROM advice_catalog "
            + "WHERE disease_id = :diseaseId AND language_code = :languageCode LIMIT 1")
    AdviceEntity findAdvice(@NonNull String diseaseId, @NonNull String languageCode);

    @Query("SELECT COUNT(*) FROM advice_catalog")
    int countEntries();
}
