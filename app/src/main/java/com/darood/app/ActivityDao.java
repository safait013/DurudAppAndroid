package com.darood.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface ActivityDao {

    @Query("SELECT * FROM activity_records "
            + "WHERE type = :type AND contentId = :contentId AND date = :date LIMIT 1")
    ActivityRecord find(String type, int contentId, String date);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ActivityRecord record);

    @Query("SELECT * FROM activity_records ORDER BY type ASC, contentId ASC, date DESC")
    List<ActivityRecord> getAll();

    /**
     * Atomically increments the daily count for (type, contentId, date), or
     * inserts a new row with count = 1 if none exists yet. Uses a transaction so
     * the read-then-write is safe under concurrent taps.
     */
    @Transaction
    default void increment(String type, int contentId, String date) {
        ActivityRecord existing = find(type, contentId, date);
        if (existing == null) {
            upsert(new ActivityRecord(type, contentId, date, 1));
        } else {
            upsert(new ActivityRecord(type, contentId, date, existing.count + 1));
        }
    }
}
