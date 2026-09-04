package com.darood.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Centralized application-wide logger.
 *
 * <p>Supports DEBUG / INFO / WARNING / ERROR levels and writes each entry to both
 * Logcat (android.util.Log) and an in-memory ring buffer. The ring buffer is the
 * source for on-device diagnostic and crash log files, so it is deliberately
 * bounded to avoid unbounded memory growth.
 *
 * <p>Privacy: callers must NOT pass passwords, tokens, API keys or any other
 * sensitive user data. Only technical diagnostics belong in these logs.
 */
public final class AppLogger {

    public enum Level { DEBUG, INFO, WARNING, ERROR }

    private static final int MAX_BUFFER_LINES = 600;

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private static boolean debuggable;

    private static final SimpleDateFormat TIMESTAMP =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private AppLogger() {
    }

    /** Must be called once from {@link App#onCreate()}. */
    public static void init(Context context) {
        try {
            debuggable = (context.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable t) {
            debuggable = false;
        }
    }

    public static void d(String tag, String message) {
        log(Level.DEBUG, tag, message, null);
    }

    public static void i(String tag, String message) {
        log(Level.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        log(Level.WARNING, tag, message, null);
    }

    public static void w(String tag, String message, Throwable t) {
        log(Level.WARNING, tag, message, t);
    }

    public static void e(String tag, String message) {
        log(Level.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable t) {
        log(Level.ERROR, tag, message, t);
    }

    private static void log(Level level, String tag, String message, Throwable t) {
        // Keep release builds free of verbose debug noise.
        if (level == Level.DEBUG && !debuggable) {
            return;
        }
        String safeTag = tag == null ? "App" : tag;
        String safeMsg = message == null ? "" : message;

        // Logcat (full stack trace is included by Log for throwables).
        switch (level) {
            case DEBUG:
                Log.d(safeTag, safeMsg, t);
                break;
            case INFO:
                Log.i(safeTag, safeMsg, t);
                break;
            case WARNING:
                Log.w(safeTag, safeMsg, t);
                break;
            case ERROR:
                Log.e(safeTag, safeMsg, t);
                break;
        }

        // In-memory ring buffer (concise exception summary, not the full trace).
        StringBuilder line = new StringBuilder()
                .append(timestamp()).append(" | ")
                .append(level.name()).append(" | ")
                .append(safeTag).append(" | ")
                .append(safeMsg);
        if (t != null) {
            line.append(" | Exception: ").append(t.getClass().getName())
                    .append(": ").append(t.getMessage() == null ? "" : t.getMessage());
        }
        synchronized (LOCK) {
            BUFFER.addLast(line.toString());
            while (BUFFER.size() > MAX_BUFFER_LINES) {
                BUFFER.removeFirst();
            }
        }
    }

    /** @return up to {@code maxLines} of the most recent buffered log lines (oldest first). */
    public static List<String> getRecentLogs(int maxLines) {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            int from = Math.max(0, BUFFER.size() - Math.max(1, maxLines));
            int i = 0;
            for (String s : BUFFER) {
                if (i++ >= from) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** True when running a debuggable (debug) build. */
    public static boolean isDebuggable() {
        return debuggable;
    }

    private static String timestamp() {
        try {
            return TIMESTAMP.format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
}
