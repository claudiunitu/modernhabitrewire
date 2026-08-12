package com.example.voward;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Controls system-level grayscale by toggling Android's built-in color correction
 * (daltonizer) via Settings.Secure. This makes the entire display go grayscale —
 * including other apps — without requiring a screen capture or overlay.
 *
 * Requires WRITE_SECURE_SETTINGS, which must be granted once via ADB:
 *   adb shell pm grant com.example.modernhabitrewire android.permission.WRITE_SECURE_SETTINGS
 */
public class GrayscaleController {

    private static final String TAG = "GrayscaleController";
    private static final String KEY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String KEY_DALTONIZER_MODE    = "accessibility_display_daltonizer";
    private static final int    DALTONIZER_GRAYSCALE   = -1;
    private static final String RECOVERY_PREFS = "display_recovery_state";
    private static final String KEY_ACTIVE = "grayscale_active";
    private static final String KEY_PREVIOUS_ENABLED = "previous_enabled";
    private static final String KEY_PREVIOUS_MODE = "previous_mode";

    private final ContentResolver resolver;
    private final SharedPreferences recoveryPrefs;
    private final String packageName;

    // Saved state so we restore whatever the user had before a session.
    private int savedDaltonizerEnabled = 0;
    private int savedDaltonizerMode    = DALTONIZER_GRAYSCALE;

    public GrayscaleController(Context context) {
        this.resolver = context.getContentResolver();
        this.packageName = context.getPackageName();
        this.recoveryPrefs = context.getApplicationContext()
                .getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE);
        restoreStaleStateIfNeeded();
    }

    public static boolean isGrayscaleAvailable(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("ApplySharedPref")
    public synchronized void setGrayscaleEnabled(boolean enabled) {
        try {
            if (enabled) {
                if (recoveryPrefs.getBoolean(KEY_ACTIVE, false)) return;
                savedDaltonizerEnabled = Settings.Secure.getInt(resolver, KEY_DALTONIZER_ENABLED, 0);
                savedDaltonizerMode    = Settings.Secure.getInt(resolver, KEY_DALTONIZER_MODE, DALTONIZER_GRAYSCALE);
                boolean journaled = recoveryPrefs.edit()
                        .putInt(KEY_PREVIOUS_ENABLED, savedDaltonizerEnabled)
                        .putInt(KEY_PREVIOUS_MODE, savedDaltonizerMode)
                        .putBoolean(KEY_ACTIVE, true)
                        .commit();
                if (!journaled) return;
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_MODE,    DALTONIZER_GRAYSCALE);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_ENABLED, 1);
            } else {
                if (!recoveryPrefs.getBoolean(KEY_ACTIVE, false)) return;
                savedDaltonizerEnabled = recoveryPrefs.getInt(KEY_PREVIOUS_ENABLED, 0);
                savedDaltonizerMode = recoveryPrefs.getInt(KEY_PREVIOUS_MODE, DALTONIZER_GRAYSCALE);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_ENABLED, savedDaltonizerEnabled);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_MODE,    savedDaltonizerMode);
                recoveryPrefs.edit().clear().apply();
            }
        } catch (SecurityException e) {
            if (enabled) recoveryPrefs.edit().clear().apply();
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — grayscale unavailable. "
                    + "Run: adb shell pm grant " + packageName + " "
                    + "android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private void restoreStaleStateIfNeeded() {
        if (!recoveryPrefs.getBoolean(KEY_ACTIVE, false)) return;
        try {
            Settings.Secure.putInt(resolver, KEY_DALTONIZER_ENABLED,
                    recoveryPrefs.getInt(KEY_PREVIOUS_ENABLED, 0));
            Settings.Secure.putInt(resolver, KEY_DALTONIZER_MODE,
                    recoveryPrefs.getInt(KEY_PREVIOUS_MODE, DALTONIZER_GRAYSCALE));
            recoveryPrefs.edit().clear().apply();
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to restore display state; secure settings access is unavailable.", e);
        }
    }
}
