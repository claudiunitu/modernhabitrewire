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
 */
public class SessionDeadlineReceiver extends BroadcastReceiver {

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
