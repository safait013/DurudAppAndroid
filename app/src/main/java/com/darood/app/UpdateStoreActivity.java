package com.darood.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Opens this app's official Play Store listing from an update notification. */
public class UpdateStoreActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String packageName = getPackageName();
        boolean launchedStore = false;
        try {
            Intent market = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName));
            if (market.resolveActivity(getPackageManager()) != null) {
                startActivity(market);
                launchedStore = true;
            } else {
                AppLogger.w("UpdateStoreActivity", "Play Store launch failure: app unavailable");
            }
        } catch (Exception e) {
            AppLogger.w("UpdateStoreActivity", "Play Store launch failure", e);
        }
        if (!launchedStore) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/apps/details?id=" + packageName)));
            } catch (Exception e) {
                AppLogger.w("UpdateStoreActivity", "Play Store web fallback launch failure", e);
            }
        }
        finish();
    }
}