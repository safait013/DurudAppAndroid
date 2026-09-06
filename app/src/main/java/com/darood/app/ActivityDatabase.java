package com.darood.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Local Room database: My Activity history plus the manual Hijri date overrides.
 *
 * <p>Version 2. Version 1→2 migration only <em>adds</em> the
 * {@code hijri_overrides} table — every existing table ([My Activity] records)
 * and all of its data is preserved. Destructive migration is never used because
 * user activity history and manual Hijri dates must survive app updates.
 */
@Database(entities = {ActivityRecord.class, HijriOverride.class}, version = 2,
        exportSchema = false)
public abstract class ActivityDatabase extends RoomDatabase {

    private static volatile ActivityDatabase instance;

    public abstract ActivityDao activityDao();

    public abstract HijriOverrideDao hijriOverrideDao();

    /**
     * Adds the manual Hijri override table. Non-destructive: existing activity
     * records are untouched. The Gregorian date ({@code yyyy-MM-dd}) is the
     * primary key / stable lookup key.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `hijri_overrides` ("
                    + "`gregorianDate` TEXT NOT NULL, "
                    + "`hijriDay` INTEGER NOT NULL, "
                    + "`hijriMonth` INTEGER NOT NULL, "
                    + "`hijriYear` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`gregorianDate`))");
        }
    };

    public static ActivityDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (ActivityDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    ActivityDatabase.class, "activity.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }
}
