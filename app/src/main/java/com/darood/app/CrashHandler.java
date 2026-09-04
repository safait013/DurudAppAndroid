package com.darood.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Global uncaught-exception handler.
 *
 * <p>Captures the crash, writes a local crash log, and (only if the user has
 * explicitly enabled it) prepares a crash-report email. It then chains to the
 * previous/default handler so the app terminates normally — it never silently
 * restarts the app.
 *
 * <p>Crash reporting here is fully defensive: any failure while writing the log
 * or preparing the email is swallowed so reporting itself can never cause a
 * second crash.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final Context context;

    private CrashHandler(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context.getApplicationContext();
        this.defaultHandler = defaultHandler;
    }

    /** Installs the handler; call once from {@link App#onCreate()}. */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) {
            return; // already installed
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            AppLogger.e("CrashHandler", "Fatal uncaught exception on thread "
                    + (thread != null ? thread.getName() : "?"), throwable);
        } catch (Throwable ignored) {
        }

        File crashFile = null;
        try {
            crashFile = LogFileManager.writeCrashLog(context, throwable);
        } catch (Throwable ignored) {
        }

        try {
            if (crashFile != null && AppSettings.getCrashReportConsent(context)) {
                prepareCrashEmail(crashFile);
            }
        } catch (Throwable ignored) {
        }

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    private void prepareCrashEmail(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_EMAIL, new String[]{AppSettings.SUPPORT_EMAIL});
            send.putExtra(Intent.EXTRA_SUBJECT, "Durood & Salam Crash Report - " + versionName());
            send.putExtra(Intent.EXTRA_TEXT,
                    "A crash diagnostic file is attached. It contains technical information "
                            + "that helps diagnose the problem.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "Send crash report");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            AppLogger.i("CrashHandler", "Crash report email prepared");
        } catch (Throwable t) {
            AppLogger.e("CrashHandler", "Failed to prepare crash report email; "
                    + "crash file kept locally", t);
        }
    }

    private String versionName() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
