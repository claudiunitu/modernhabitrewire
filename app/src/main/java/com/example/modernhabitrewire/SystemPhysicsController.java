package com.example.modernhabitrewire;

import android.content.ContentResolver;
import android.content.Context;
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
public class SystemPhysicsController {

    private static final String TAG = "SystemPhysicsController";
    private static final String KEY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String KEY_DALTONIZER_MODE    = "accessibility_display_daltonizer";
    private static final int    DALTONIZER_GRAYSCALE   = -1;

    private final ContentResolver resolver;

    // Saved state so we restore whatever the user had before a session.
    private int savedDaltonizerEnabled = 0;
    private int savedDaltonizerMode    = DALTONIZER_GRAYSCALE;

    public SystemPhysicsController(Context context) {
        this.resolver = context.getContentResolver();
    }

    public synchronized void setGrayscaleEnabled(boolean enabled) {
        try {
            if (enabled) {
                savedDaltonizerEnabled = Settings.Secure.getInt(resolver, KEY_DALTONIZER_ENABLED, 0);
                savedDaltonizerMode    = Settings.Secure.getInt(resolver, KEY_DALTONIZER_MODE, DALTONIZER_GRAYSCALE);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_MODE,    DALTONIZER_GRAYSCALE);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_ENABLED, 1);
            } else {
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_ENABLED, savedDaltonizerEnabled);
                Settings.Secure.putInt(resolver, KEY_DALTONIZER_MODE,    savedDaltonizerMode);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — grayscale unavailable. "
                    + "Run: adb shell pm grant com.example.modernhabitrewire "
                    + "android.permission.WRITE_SECURE_SETTINGS");
        }
    }
}
