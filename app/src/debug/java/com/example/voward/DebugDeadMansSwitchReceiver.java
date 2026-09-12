package com.example.voward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Debug builds only: winds the dead man's switch health check back far enough to expire it.
 *
 * <p>The switch is the last thing standing between the user and a factory reset if Voward ever
 * stops working, and it is the hardest part of the app to see working — the threshold is days,
 * and the clock guard plus a Play-image emulator make moving the device clock impossible. So
 * the rungs above it were all observed in testing and this one never was.</p>
 *
 * <p>This exists only in the debug source set, so a release build has no such receiver at all:
 *
 * <pre>adb shell am broadcast -a com.example.voward.debug.EXPIRE_DEAD_MANS_SWITCH \
 *     -n com.example.voward/.DebugDeadMansSwitchReceiver</pre>
 *
 * <p>The next reconcile then finds the switch expired and releases everything, which is exactly
 * the path a real expiry takes.</p>
 */
public class DebugDeadMansSwitchReceiver extends BroadcastReceiver {

    private static final String TAG = "DebugDeadMansSwitch";
    private static final String ACTION = "com.example.voward.debug.EXPIRE_DEAD_MANS_SWITCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        Context appContext = context.getApplicationContext();
        PendingResult result = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                AppPreferencesManagerSingleton preferences =
                        AppPreferencesManagerSingleton.getInstance(appContext);
                // One day past the configured threshold, on both clocks, in the boot the device
                // is in now: aged rather than faked, so the real comparison is what decides.
                long ageMs = TimeUnit.DAYS.toMillis(
                        DeadMansSwitchPolicy.clampDays(preferences.getDeadMansSwitchDays()) + 1L);
                preferences.setDeadMansSwitchCheck(System.currentTimeMillis() - ageMs,
                        android.os.SystemClock.elapsedRealtime() - ageMs,
                        preferences.getDeadMansSwitchBootCount());
                Log.w(TAG, "Dead man's switch aged by " + ageMs + "ms; reconciling");
                EnforcementCoordinator.reconcileNow(appContext);
            } finally {
                result.finish();
            }
        });
        executor.shutdown();
    }
}
