package com.darood.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

/**
 * First-launch language selection screen. Shown once, before the user starts
 * using the app; the choice is saved via {@link LanguageManager} and this
 * screen is never shown again on normal launches.
 *
 * It can also be opened for a result (EXTRA_FROM_SETTINGS) later, so a future
 * Settings screen can reuse it to change the language.
 */
public class LanguagePickerActivity extends Activity {

    /** Boolean extra: when true, the result is returned to the caller instead of starting MainActivity. */
    public static final String EXTRA_FROM_SETTINGS = "from_settings";

    @Override
    protected void attachBaseContext(Context base) {
        // Before the first choice no language is saved: follow the device language
        // when supported (otherwise default English resources).
        super.attachBaseContext(LanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Same full-screen look as MainActivity
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().setStatusBarColor(0xFF1A3A2A); // Dark green
        }

        setContentView(R.layout.activity_language_select);

        bind(R.id.btn_lang_en, "en");
        bind(R.id.btn_lang_bn, "bn");
        bind(R.id.btn_lang_ur, "ur");
    }

    private void bind(int buttonId, final String languageCode) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(languageCode);
            }
        });
    }

    private void select(String languageCode) {
        AppLogger.i("LanguagePickerActivity", "Language selected: " + languageCode);
        LanguageManager.saveLanguage(this, languageCode);
        if (getIntent().getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            // Settings mode: hand the choice back; the caller recreates its UI.
            setResult(RESULT_OK, new Intent().putExtra(LanguageManager.KEY_LANGUAGE, languageCode));
            finish();
        } else {
            // First launch: enter the app with the chosen language.
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }
}