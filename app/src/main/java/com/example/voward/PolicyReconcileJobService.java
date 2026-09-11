package com.example.voward;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * The periodic reconcile. Enforcement is state in system_server, so nothing needs to stay
 * resident to hold a block: this wakes up, makes the device match the desired state, and
 * finishes.
 */
public class PolicyReconcileJobService extends JobService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            EnforcementCoordinator.reconcileNow(getApplicationContext());
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The next tick reconciles from scratch, so an interrupted pass needs no retry.
        return false;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
