package com.darood.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes readable diagnostic and crash log files into app-private storage
 * ({@code filesDir/logs}) and enforces a simple retention policy so logs never
 * grow without bound. No storage permission is required.
 */
public final class LogFileManager {

    private static final String DIR_NAME = "logs";
    private static final int MAX_LOG_FILES = 12;

    private static final SimpleDateFormat FILE_TIMESTAMP =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private LogFileManager() {
    }

    /** @return the app-private logs directory (created on demand). */
    public static File logsDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Generates a comprehensive diagnostic log file. Returns the file, or null on failure. */
    public static File writeDiagnosticLog(Context context) {
        try {
            enforceRetention(context);
            File file = new File(logsDir(context),
                    "app_log_" + FILE_TIMESTAMP.format(new Date()) + ".txt");
            FileWriter writer = new FileWriter(file, false);
            writer.write(buildDiagnosticContent(context));
            writer.flush();
            writer.close();
            AppLogger.i("LogFileManager", "Diagnostic log written: " + file.getName());
            return file;
        } catch (Throwable t) {
            AppLogger.e("LogFileManager", "Failed to write diagnostic log", t);
            return null;
        }
    }

    /** Writes a crash log for an uncaught exception. Returns the file, or null on failure. */
    public static File writeCrashLog(Context context, Throwable crash) {
        try {
            enforceRetention(context);
            File file = new File(logsDir(context),
                    "crash_" + FILE_TIMESTAMP.format(new Date()) + ".txt");
            FileWriter writer = new FileWriter(file, false);
            writer.write(buildCrashContent(context, crash));
            writer.flush();
            writer.close();
            AppLogger.i("LogFileManager", "Crash log written: " + file.getName());
            return file;
        } catch (Throwable t) {
            // Never throw from crash handling; the caller already has a fatal exception.
            return null;
        }
    }

    /** Resolves a previously generated log file by its name (inside the logs dir). */
    public static File resolve(Context context, String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            return null; // reject path traversal
        }
        return new File(logsDir(context), fileName);
    }

    private static String buildDiagnosticContent(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Durood & Salam - Diagnostic Log\n");
        sb.append("================================\n\n");

        sb.append("## App Information\n");
        sb.append("Generated: ").append(now()).append("\n");
        sb.append("App Version: ").append(versionName(context)).append("\n");
        sb.append("Android Version: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");

        sb.append("## Configuration\n");
        sb.append("Language: ").append(safe(LanguageManager.getSavedLanguage(context))).append("\n");
        sb.append("Theme: ").append(safe(AppSettings.getTheme(context))).append("\n");
        sb.append("Arabic Font: ").append(safe(AppSettings.getArabicFont(context))).append("\n");
        sb.append("Arabic Font Size: ").append(AppSettings.getArabicFontSize(context)).append("\n");
        sb.append("Crash Report Email Consent: ")
                .append(AppSettings.getCrashReportConsent(context)).append("\n");
        try {
            sb.append("Notifications: ")
                    .append(NotificationScheduler.getConfig(context).toString()).append("\n");
        } catch (Throwable t) {
            sb.append("Notifications: unavailable\n");
        }
        sb.append("\n");

        sb.append("## Runtime\n");
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        sb.append("Heap Used: ").append(mb(used)).append(" MB\n");
        sb.append("Heap Total: ").append(mb(rt.totalMemory())).append(" MB\n");
        sb.append("Heap Max: ").append(mb(rt.maxMemory())).append(" MB\n\n");

        sb.append("## Recent Logs\n");
        List<String> logs = AppLogger.getRecentLogs(400);
        for (String line : logs) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String buildCrashContent(Context context, Throwable crash) {
        StringBuilder sb = new StringBuilder();
        sb.append("Durood & Salam - Crash Report\n");
        sb.append("=============================\n\n");

        sb.append("## Crash Information\n");
        sb.append("Timestamp: ").append(now()).append("\n");
        sb.append("App Version: ").append(versionName(context)).append("\n");
        sb.append("Android Version: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n\n");

        sb.append("## Exception\n");
        if (crash != null) {
            sb.append("Type: ").append(crash.getClass().getName()).append("\n");
            sb.append("Message: ").append(safe(crash.getMessage())).append("\n\n");
            sb.append("## Stack Trace\n");
            sb.append(Log.getStackTraceString(crash)).append("\n\n");
        }

        sb.append("## Recent Logs\n");
        List<String> logs = AppLogger.getRecentLogs(300);
        for (String line : logs) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static void enforceRetention(Context context) {
        try {
            File dir = logsDir(context);
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int excess = files.length - MAX_LOG_FILES;
            for (int i = 0; i < excess; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
            // retention is best-effort; never throw from it
        }
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String safe(String s) {
        return s == null ? "(none)" : s;
    }
}
