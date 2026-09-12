package com.example.voward;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * The "Alarms &amp; reminders" permission, which a session deadline needs to end on time.
 *
 * <p>{@code SCHEDULE_EXACT_ALARM} is declared in the manifest but Android 12 and later do not
 * grant it at install, and nothing in the app asked for it. Every deadline therefore fell back
 * to an inexact alarm: measured on a Pixel 10, a five minute session ran five minutes and
 * forty-three seconds. The two minute reconcile poll bounds the overrun but does not remove
 * it, and the extra time is free.</p>
 */
final class AlarmPermission {

    private AlarmPermission() {}

    /** True below Android 12, where the permission is granted at install and cannot be revoked. */
    static boolean isGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    /** The settings screen that grants it, or null where there is nothing to ask for. */
    static Intent requestIntent(Context context) {
        if (isGranted(context)) return null;
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + context.getPackageName()));
    }
}
