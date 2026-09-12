package com.example.voward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ends an approved session when its exact alarm fires.
 *
 * <p>The deadline lives in {@code AlarmManager} rather than in a running timer, so a session
 * ends on time even if Voward was killed the moment after it started.</p>
 *
 * <p>It also takes the "End session now" action on the ongoing notification. Both mean the same
 * thing — stop the clock and put the restriction back — so both land here. The receiver is not
 * exported, so the only senders are the alarm and that notification.</p>
 */
public class SessionDeadlineReceiver extends BroadcastReceiver {

    /** The notification action, kept distinct from the alarm so cancelling one leaves the other. */
    static final String ACTION_END_SESSION_NOW = "com.example.voward.action.END_SESSION_NOW";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
        PendingResult result = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                EnforcementCoordinator.endSession(appContext);
            } finally {
                result.finish();
            }
        });
        executor.shutdown();
    }
}
