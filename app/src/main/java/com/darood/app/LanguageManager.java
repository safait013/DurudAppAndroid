package com.darood.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Persistence + application of the in-app language ("en", "bn", "ur").
 *
 * Storage: SharedPreferences ("app_settings") - the first persistence mechanism
 * in this project, so no extra dependency (e.g. DataStore) is needed. The choice
 * survives app restarts, activity recreation and process recreation because it
 * is read in attachBaseContext() before any resource is resolved.
 *
 * Arabic religious content is never localized; this only controls UI/explanatory
 * language.
 */
public final class LanguageManager {

    /** Shared prefs file; future settings (theme/font/notifications) can live here too. */
    public static final String PREFS_FILE = "app_settings";

    /** Key under which the selected language code ("en" / "bn" / "ur") is stored. */
    public static final String KEY_LANGUAGE = "app_language";

    /** The three supported UI languages. */
    public static final String[] SUPPORTED_LANGUAGES = {"en", "bn", "ur"};

    private LanguageManager() {
    }

    /** @return the saved language code, or null while the user has not chosen one yet. */
    public static String getSavedLanguage(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, null);
    }

    /** True once the user has completed the first-launch language selection. */
    public static boolean isLanguageSelected(Context context) {
        return getSavedLanguage(context) != null;
    }

    /** Persists the language choice (picker button or the JS bridge changeLanguage). */
    public static void saveLanguage(Context context, String languageCode) {
        AppLogger.i("LanguageManager", "Language saved: " + languageCode);
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply();
    }

    /** True if the given code is one of the supported languages. */
    public static boolean isSupported(String languageCode) {
        for (String code : SUPPORTED_LANGUAGES) {
            if (code.equals(languageCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps the base context so every getString()/resource lookup resolves in the
     * saved in-app language. Before the first choice English is used
     * without changing an existing saved preference.
     * Call from attachBaseContext() of every activity.
     */
    @SuppressWarnings("deprecation") // Configuration.setLocale is required on API 21–23 (minSdk).
    public static Context applyLanguage(Context base) {
        String saved = getSavedLanguage(base);
        Locale target = new Locale(saved != null && isSupported(saved) ? saved : "en");
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(target));
        } else {
            config.setLocale(target);
        }
        // Layout direction follows the locale: RTL for Urdu, LTR for English/Bangla.
        config.setLayoutDirection(target);
        return base.createConfigurationContext(config);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}