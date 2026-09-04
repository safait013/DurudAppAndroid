package com.darood.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin repository over {@link ActivityDatabase}. All database work runs on a
 * single background thread so it never blocks the UI thread; failures are logged
 * and swallowed so a database error can never break the Durood/Salam counters.
 */
public final class ActivityRepository {

    public static final String TYPE_DUROOD = "durood";
    public static final String TYPE_SALAM = "salam";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile ActivityRepository instance;

    private final ActivityDao dao;

    private ActivityRepository(Context context) {
        dao = ActivityDatabase.getInstance(context).activityDao();
        AppLogger.i("ActivityRepository", "Activity database initialized");
    }

    public static ActivityRepository get(Context context) {
        if (instance == null) {
            synchronized (ActivityRepository.class) {
                if (instance == null) {
                    instance = new ActivityRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Records one read for the given content on today's local date (async). */
    public void record(String type, int contentId) {
        final String date = today();
        EXECUTOR.execute(() -> {
            try {
                dao.increment(type, contentId, date);
                AppLogger.d("ActivityRepository",
                        "Activity recorded: " + type + " #" + contentId + " on " + date);
            } catch (Throwable t) {
                AppLogger.e("ActivityRepository",
                        "Failed to record activity (" + type + " #" + contentId + ")", t);
            }
        });
    }

    /** Loads all activity records on a background thread and invokes the callback. */
    public void load(Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                callback.onResult(dao.getAll());
            } catch (Throwable t) {
                AppLogger.e("ActivityRepository", "Failed to load activity", t);
                callback.onResult(new ArrayList<>());
            }
        });
    }

    public interface Callback {
        void onResult(List<ActivityRecord> records);
    }

    private static String today() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        } catch (Throwable t) {
            return "1970-01-01";
        }
    }
}
