package com.example.voward;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/**
 * Measures how long a package was actually in the foreground, pulled once at the end of a
 * session instead of polled.
 *
 * <p>The old model charged for wall time between approval and eviction, which meant a user
 * who opened an app and put the phone down paid full price. Usage access answers the honest
 * question: how much of the session was really spent in that app.</p>
 */
final class UsageMeter {

    private UsageMeter() {}

    /**
     * Usage access is granted from Android Settings, not from a computer, and can be revoked
     * at any time. Callers fall back to the quoted duration when it is missing.
     */
    static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        // unsafeCheckOpNoThrow is the API 29 rename of checkOpNoThrow; both are no-throw
        // reads of the same appop, and minSdk is 26.
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName())
                : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Foreground milliseconds for {@code packageName} between two wall-clock instants.
     *
     * <p>Returns -1 when usage access is unavailable, which is different from a measured
     * zero: the caller must charge the quoted duration rather than nothing.</p>
     */
    static long foregroundMillis(Context context, String packageName,
                                 long startWallMs, long endWallMs) {
        if (packageName == null || packageName.isEmpty() || endWallMs <= startWallMs) return 0;
        if (!hasUsageAccess(context)) return -1;
        UsageStatsManager usage = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return -1;

        UsageEvents events;
        try {
            events = usage.queryEvents(startWallMs, endWallMs);
        } catch (RuntimeException unavailable) {
            return -1;
        }
        if (events == null) return -1;

        long total = 0;
        long enteredAt = 0;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (!packageName.equals(event.getPackageName())) continue;
            // MOVE_TO_FOREGROUND/BACKGROUND are the API 26 spellings of the constants later
            // renamed to ACTIVITY_RESUMED/ACTIVITY_PAUSED. Same values, still delivered.
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                enteredAt = event.getTimeStamp();
            } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND
                    && enteredAt > 0) {
                total += Math.max(0, event.getTimeStamp() - enteredAt);
                enteredAt = 0;
            }
        }
        // Still in the foreground when the window closed: count up to the end of the window.
        if (enteredAt > 0) total += Math.max(0, endWallMs - enteredAt);
        return Math.min(total, endWallMs - startWallMs);
    }
}
