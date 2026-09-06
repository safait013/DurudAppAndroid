package com.darood.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin synchronous repository over {@link HijriOverrideDao}. Every method is
 * called from {@link HijriCoordinator}'s single background executor (never from
 * the UI/JS thread); database failures are logged and swallowed so manual
 * overrides can never break the automatic date.
 */
public final class HijriOverrideRepository {

    private static volatile HijriOverrideRepository instance;

    private final HijriOverrideDao dao;

    private HijriOverrideRepository(Context context) {
        dao = ActivityDatabase.getInstance(context).hijriOverrideDao();
    }

    public static HijriOverrideRepository get(Context context) {
        if (instance == null) {
            synchronized (HijriOverrideRepository.class) {
                if (instance == null) {
                    instance = new HijriOverrideRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** @return the manual override for a Gregorian date, or null when none exists. */
    public HijriOverride find(String gregorianDate) {
        try {
            return dao.find(gregorianDate);
        } catch (Throwable t) {
            AppLogger.e("HijriOverrideRepository", "find failed for " + gregorianDate, t);
            return null;
        }
    }

    /** @return every manual override (newest Gregorian date first), never null. */
    public List<HijriOverride> getAll() {
        try {
            return dao.getAll();
        } catch (Throwable t) {
            AppLogger.e("HijriOverrideRepository", "getAll failed", t);
            return new ArrayList<>();
        }
    }

    /** Inserts or replaces the manual override for its Gregorian date. */
    public void upsert(HijriOverride override) {
        try {
            dao.upsert(override);
            AppLogger.i("HijriOverrideRepository", "Upserted override for " + override.gregorianDate
                    + " = " + override.hijriDay + "-" + override.hijriMonth + "-" + override.hijriYear);
        } catch (Throwable t) {
            AppLogger.e("HijriOverrideRepository", "upsert failed for " + override.gregorianDate, t);
        }
    }

    /** Deletes the manual override for a Gregorian date. */
    public void delete(String gregorianDate) {
        try {
            dao.delete(gregorianDate);
            AppLogger.i("HijriOverrideRepository", "Deleted override for " + gregorianDate);
        } catch (Throwable t) {
            AppLogger.e("HijriOverrideRepository", "delete failed for " + gregorianDate, t);
        }
    }
}