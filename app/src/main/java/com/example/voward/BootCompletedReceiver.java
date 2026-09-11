package com.example.voward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reconciles once at boot and re-registers the periodic job.
 *
 * <p>Restrictions themselves survive a reboot without any help — they live in system_server.
 * This exists so a state that was half applied when the device went down heals immediately
 * rather than at the next job tick, and so the dead man's switch is evaluated early.</p>
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context appContext = context.getApplicationContext();
        PendingResult result = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                EnforcementCoordinator.schedulePeriodicReconcile(appContext);
                EnforcementCoordinator.reconcileNow(appContext);
            } finally {
                result.finish();
            }
        });
        // Lets the queued pass finish, then retires the thread rather than leaving it idle.
        executor.shutdown();
    }
}
