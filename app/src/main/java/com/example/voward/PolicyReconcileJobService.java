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
            // After finishing, never before: rescheduling an id that is still running would
            // stop the run that is doing it. The short poll is a one-shot, so this is the link
            // that keeps the chain going.
            EnforcementCoordinator.scheduleFastReconcile(getApplicationContext());
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // An interrupted pass reconciles from scratch next time and needs no retry of its own,
        // but the short poll only survives by being rescheduled, and being stopped is exactly
        // the case where the code above never ran.
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
