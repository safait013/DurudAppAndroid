package com.darood.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Local Room database for My Activity history.
 *
 * <p>Version 1. Future schema changes MUST add migrations (see Room
 * {@code addMigrations(...)}); do NOT use {@code fallbackToDestructiveMigration()}
 * because user activity history must survive app updates.
 */
@Database(entities = {ActivityRecord.class}, version = 1, exportSchema = false)
public abstract class ActivityDatabase extends RoomDatabase {

    private static volatile ActivityDatabase instance;

    public abstract ActivityDao activityDao();

    public static ActivityDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ActivityDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    ActivityDatabase.class, "activity.db")
                            .build();
                }
            }
        }
        return instance;
    }
}
