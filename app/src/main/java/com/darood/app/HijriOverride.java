package com.darood.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;

/**
 * One manual Hijri date override keyed by the Gregorian date (uniquely stable
 * lookup key, {@code yyyy-MM-dd}).
 *
 * <p>A manual override always takes priority over the automatic AlAdhan date for
 * its Gregorian day. Persisted in Room (activity.db) so it survives app restarts,
 * language/theme/font changes and app updates, together with the existing
 * My Activity / Durood / Salam counters (non-destructive migration).
 */
@Entity(tableName = "hijri_overrides", primaryKeys = {"gregorianDate"})
public class HijriOverride {

    /** Gregorian date this manual Hijri date applies to ({@code yyyy-MM-dd}). */
    @NonNull
    public String gregorianDate;

    /** Hijri day (1–30). */
    public int hijriDay;

    /** Hijri month number (1–12, Muharram = 1). */
    public int hijriMonth;

    /** Hijri year (AH). */
    public int hijriYear;

    /** Epoch millis of the last create/update. */
    public long updatedAt;

    public HijriOverride() {
    }

    @Ignore
    public HijriOverride(String gregorianDate, int hijriDay, int hijriMonth, int hijriYear,
                         long updatedAt) {
        this.gregorianDate = gregorianDate;
        this.hijriDay = hijriDay;
        this.hijriMonth = hijriMonth;
        this.hijriYear = hijriYear;
        this.updatedAt = updatedAt;
    }
}