package com.darood.app;

import android.content.Context;

import java.text.SimpleDateFormat;
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
        record(type, contentId, null);
    }

    public interface WriteCallback {
        void onComplete(boolean saved);
    }

    public void record(String type, int contentId, WriteCallback callback) {
        if (!TYPE_DUROOD.equals(type) && !TYPE_SALAM.equals(type)) {
            AppLogger.w("ActivityRepository", "Ignoring invalid activity type: " + type);
            if (callback != null) callback.onComplete(false);
            return;
        }
        if (contentId <= 0) {
            AppLogger.w("ActivityRepository", "Ignoring invalid contentId: " + contentId);
            if (callback != null) callback.onComplete(false);
            return;
        }
        final String date = today();
        AppLogger.i("ActivityRepository", "DB increment requested: " + type + " #" + contentId);
        EXECUTOR.execute(() -> {
            boolean saved = false;
            try {
                dao.increment(type, contentId, date);
                saved = true;
                AppLogger.i("ActivityRepository",
                        "DB increment succeeded: " + type + " #" + contentId + " on " + date);
            } catch (Throwable t) {
                AppLogger.e("ActivityRepository",
                        "DB increment failed (" + type + " #" + contentId + ")", t);
            }
            if (callback != null) callback.onComplete(saved);
        });
    }

    /** Loads all activity records on a background thread and invokes the callback. */
    public void load(Callback callback) {
        EXECUTOR.execute(() -> {

            try {
                AppLogger.i("ActivityRepository", "My Activity DB query started");
                List<ActivityRecord> records = dao.getAll();
                AppLogger.i("ActivityRepository", records.isEmpty()
                        ? "My Activity DB result empty" : "Activity data loaded; rows=" + records.size());
                callback.onResult(records);
            } catch (Throwable t) {
                AppLogger.e("ActivityRepository", "Failed to load activity", t);
                callback.onResult(null); // Query failure is different from an empty database.
            }
        });
    }

    public interface Callback {
        void onResult(List<ActivityRecord> records);
    }

    private static String today() {
        // Capture the device local date at the tap, before queuing the write.
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}