package com.darood.app;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;

/**
 * Application entry point: initializes the central logger, installs the global
 * crash handler, and enables StrictMode in debuggable builds for lightweight
 * memory-leak / threading diagnostics.
 *
 * <p>Note: full automatic memory-leak detection (e.g. LeakCanary) is intentionally
 * not used because this app is a lightweight WebView application; StrictMode
 * plus proper resource cleanup provides an appropriate, low-overhead alternative
 * in debug builds without affecting release performance.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.init(this);
        CrashHandler.install(this);
        if (AppLogger.isDebuggable()) {
            enableStrictMode();
        }
        AppLogger.i("App", "Application started");
        // Asynchronous and cached: this cannot delay launch or run on every screen load.
        UpdateChecker.checkIfDue(this);
    }

    @SuppressWarnings("deprecation")
    private void enableStrictMode() {
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.VmPolicy.Builder vm = new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectActivityLeaks();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                vm.detectLeakedRegistrationObjects();
            }
            StrictMode.setVmPolicy(vm.penaltyLog().build());
            AppLogger.i("App", "StrictMode enabled (debug build)");
        } catch (Throwable t) {
            AppLogger.w("App", "StrictMode could not be enabled", t);
        }
    }
}
