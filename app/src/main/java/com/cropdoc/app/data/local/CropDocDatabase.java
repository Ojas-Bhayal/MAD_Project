package com.cropdoc.app.data.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.cropdoc.app.data.local.dao.AdviceDao;
import com.cropdoc.app.data.local.dao.ScanDao;
import com.cropdoc.app.data.local.entity.AdviceEntity;
import com.cropdoc.app.data.local.entity.ScanEntity;

/**
 * Room 2.8.5 database acting as the single offline source of truth for scans and advice.
 */
@Database(
        entities = {ScanEntity.class, AdviceEntity.class},
        version = 1,
        exportSchema = true)
public abstract class CropDocDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "cropdoc_offline.db";
    private static volatile CropDocDatabase instance;

    @NonNull
    public abstract ScanDao scanDao();

    @NonNull
    public abstract AdviceDao adviceDao();

    @NonNull
    public static CropDocDatabase getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (CropDocDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    CropDocDatabase.class,
                                    DATABASE_NAME)
                            .fallbackToDestructiveMigration(true)
                            .build();
                }
            }
        }
        return instance;
    }
}
