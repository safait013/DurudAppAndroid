package com.darood.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;

/**
 * One aggregated daily activity record for a single Durood or Salam.
 *
 * <p>Primary key is the composite (type, contentId, date), so reading the same
 * content again on the same day updates the existing row's count instead of
 * creating a new row for every tap.
 *
 * <p>{@code contentId} is the stable 1-based number of the Durood/Salam in the
 * existing content data (the Arabic text itself is never stored here).
 */
@Entity(tableName = "activity_records",
        primaryKeys = {"type", "contentId", "date"},
        indices = {@Index("date")})
public class ActivityRecord {

    /** {@code "durood"} or {@code "salam"}. */
    @NonNull
    public String type;

    /** 1-based content number (stable identifier of the Durood/Salam). */
    public int contentId;

    /** Local calendar date as {@code yyyy-MM-dd}. */
    @NonNull
    public String date;

    /** Number of times read on that date. */
    public int count;

    public ActivityRecord() {
    }

    @Ignore
    public ActivityRecord(String type, int contentId, String date, int count) {
        this.type = type;
        this.contentId = contentId;
        this.date = date;
        this.count = count;
    }
}
