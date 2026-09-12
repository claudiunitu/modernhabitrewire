package com.example.voward;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The one sequence that turns preferences into device state: check the dead man's switch,
 * reconcile, record that Voward is alive.
 *
 * <p>Every caller — activation, deactivation, boot, and the periodic job — goes through here
 * so the order cannot drift between them.</p>
 */
final class EnforcementCoordinator {

    private static final String TAG = "EnforcementCoordinator";
    private static final int RECONCILE_JOB_ID = 4411;
    private static final int SESSION_DEADLINE_REQUEST = 4412;
    private static final int FAST_RECONCILE_JOB_ID = 4413;
    private static final long RECONCILE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long FAST_RECONCILE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2);

    private EnforcementCoordinator() {}

    /**
     * Applies what protection currently asks for, unless the dead man's switch has run out,
     * in which case it releases instead. Safe to call when Voward is not the device owner:
     * the reconciler simply reports that it applied nothing.
     */
    static void reconcileNow(Context context) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        PolicyReconciler reconciler = new PolicyReconciler(context);

        if (hasDeadMansSwitchExpired(context, preferences)) {
            Log.w(TAG, "Dead man's switch expired; releasing every restriction");
            releaseNow(context);
            return;
        }

        restoreInterruptedEviction(preferences, reconciler);

        // A session whose deadline passed while nothing was running ends here, so a missed
        // alarm costs at most one tick rather than an open-ended free pass.
        if (hasExpiredSession(preferences)) endSession(context);

        // A clock shift or a reboot invalidates a pending deactivation request. The service that
        // used to watch for TIME_CHANGED is gone, so the reconcile carries this now: it runs on
        // boot and every fifteen minutes, which is well inside any confirmation window.
        DeactivationRequestValidator.validate(context, preferences);
        NewAppQuarantine.sweep(context, preferences);

        boolean applied = reconciler.reconcile(desiredState(context, preferences));
        StatusNotifier.refresh(context, preferences);
        if (applied) {
            recordHealthCheck(context, preferences);
            recordRetiredKeywordRules(preferences);
        }
    }

    private static EnforcementState desiredState(Context context,
                                                 AppPreferencesManagerSingleton preferences) {
        return EnforcementState.desiredFor(preferences, context.getPackageName(),
                SafetyPolicy.criticalPackages(context),
                BrowserPolicy.survey(context, preferences));
    }

    /**
     * Applies a "test this browser" verdict: reconcile so a newly rejected browser is paused
     * and a newly approved one starts filtering, then make certain the sentinel rule the test
     * wrote is gone.
     */
    static void applyBrowserVerdict(Context context, String browser) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        reconcileNow(context);
        new PolicyReconciler(context).restoreBrowserPolicy(browser,
                desiredState(context, preferences));
    }

    /**
     * Lifts the suspension on one package for an approved session and arms the deadline.
     *
     * <p>The deadline is an exact alarm rather than a timer in a resident process: enforcement
     * has to end on time whether or not Voward is still running.</p>
     */
    static void startSession(Context context, String packageName, long quotedSeconds) {
        startSession(context, packageName, null, quotedSeconds);
    }

    /**
     * A website session lifts one rule out of {@code URLBlocklist} instead of unsuspending a
     * package. {@code packageName} is still the browser, because that is what the meter has to
     * watch for the foreground time to mean anything.
     */
    static void startSession(Context context, String packageName, String urlPattern,
                             long quotedSeconds) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        if (packageName == null || packageName.isEmpty() || quotedSeconds <= 0) return;

        long deadlineElapsed = SystemClock.elapsedRealtime()
                + TimeUnit.SECONDS.toMillis(quotedSeconds);
        preferences.startManagedSession(packageName, urlPattern, quotedSeconds,
                System.currentTimeMillis(), deadlineElapsed);
        new PolicyReconciler(context).reconcile(desiredState(context, preferences));
        armDeadline(context, deadlineElapsed);
        StatusNotifier.refresh(context, preferences);
        Log.i(TAG, "Session started for " + packageName
                + (urlPattern == null || urlPattern.isEmpty() ? "" : " (" + urlPattern + ")")
                + ", " + quotedSeconds + "s");
    }

    /**
     * Charges the allowance for the time actually spent in the app and puts the suspension
     * back. Without usage access there is nothing to measure, so the quoted duration is
     * charged in full — the honest reading when the alternative is charging nothing.
     */
    static void endSession(Context context) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        String packageName = preferences.getManagedSessionPackage();
        if (packageName.isEmpty()) return;

        long quotedSeconds = preferences.getManagedSessionQuotedSeconds();
        long startWallMs = preferences.getManagedSessionStartWallMs();
        boolean wasWebsiteSession = !preferences.getManagedSessionUrlPattern().isEmpty();
        long measuredMs = UsageMeter.foregroundMillis(context, packageName, startWallMs,
                System.currentTimeMillis());
        long chargedMs = measuredMs < 0 ? TimeUnit.SECONDS.toMillis(quotedSeconds)
                : Math.min(measuredMs, TimeUnit.SECONDS.toMillis(quotedSeconds));

        AttentionBudgetEngine budget = new AttentionBudgetEngine(context);
        long chargedSeconds = budget.calculateUsageSeconds(chargedMs);
        budget.recordUsageDelta(chargedMs, chargedSeconds);
        preferences.recordSessionOutcome(chargedSeconds >= quotedSeconds);

        preferences.clearManagedSession();
        cancelDeadline(context);
        EnforcementState desired = desiredState(context, preferences);
        PolicyReconciler reconciler = new PolicyReconciler(context);
        reconciler.reconcile(desired);
        if (wasWebsiteSession) evictBrowsers(preferences, reconciler, desired);
        StatusNotifier.refresh(context, preferences);
        Log.i(TAG, "Session ended for " + packageName + ", charged " + chargedSeconds + "s"
                + (measuredMs < 0 ? " (no usage access; quoted duration charged)" : ""));
    }

    /**
     * Ends a website session on screen and not only in policy.
     *
     * <p>The rule is back in {@code URLBlocklist} by the time this runs, but Chromium checks
     * that on navigation, and a feed the user is already scrolling never navigates again. The
     * page would go on working until the user happened to type a new address, which for an
     * infinite feed is never. Restarting the browsers the rule was lifted from forces the tab
     * to be restored, and that restore is the navigation the policy catches.</p>
     *
     * <p>Every managed browser, not just the one the session was metered against: the rule was
     * lifted from all of them, so the page could be open in any of them. A browser under a
     * strict rule is left alone — putting the hide back would undo the rule.</p>
     */
    private static void evictBrowsers(AppPreferencesManagerSingleton preferences,
                                      PolicyReconciler reconciler, EnforcementState desired) {
        Set<String> targets = new HashSet<>(desired.managedBrowsers);
        targets.removeAll(desired.hiddenPackages);
        if (targets.isEmpty()) return;
        preferences.setPendingBrowserEviction(targets);
        try {
            reconciler.evictBrowsers(targets);
        } finally {
            preferences.clearPendingBrowserEviction();
        }
    }

    /**
     * Puts back a browser that an eviction hid and never got to unhide. Nothing else would:
     * the hide is deliberately outside the mirror, so a reconcile cannot see it.
     */
    private static void restoreInterruptedEviction(AppPreferencesManagerSingleton preferences,
                                                   PolicyReconciler reconciler) {
        Set<String> pending = preferences.getPendingBrowserEviction();
        if (pending.isEmpty()) return;
        Log.w(TAG, "Restoring " + pending.size() + " browser(s) from an interrupted eviction");
        reconciler.restoreEvictedBrowsers(pending);
        preferences.clearPendingBrowserEviction();
    }

    private static boolean hasExpiredSession(AppPreferencesManagerSingleton preferences) {
        return !preferences.getManagedSessionPackage().isEmpty()
                && SystemClock.elapsedRealtime()
                >= preferences.getManagedSessionDeadlineElapsedMs();
    }

    private static void armDeadline(Context context, long deadlineElapsedMs) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent onDeadline = deadlineIntent(context);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarms.canScheduleExactAlarms();
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        deadlineElapsedMs, onDeadline);
            } else {
                // Inexact still fires, just later. The periodic reconcile catches an overrun
                // session either way, so the worst case is a few extra minutes, not a free pass.
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        deadlineElapsedMs, onDeadline);
                Log.w(TAG, "Exact alarms unavailable; session deadline may run late");
            }
        } catch (SecurityException refused) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsedMs,
                    onDeadline);
            Log.w(TAG, "Exact alarm refused; session deadline may run late", refused);
        }
    }

    private static void cancelDeadline(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(deadlineIntent(context));
    }

    private static PendingIntent deadlineIntent(Context context) {
        return PendingIntent.getBroadcast(context, SESSION_DEADLINE_REQUEST,
                new Intent(context, SessionDeadlineReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Clears every restriction and marks protection inactive. */
    static void releaseNow(Context context) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        PolicyReconciler reconciler = new PolicyReconciler(context);
        restoreInterruptedEviction(preferences, reconciler);
        reconciler.release();
        preferences.setIsBlockerActive(false);
        preferences.clearManagedSession();
        cancelDeadline(context);
        preferences.clearDeadMansSwitchCheck();
        preferences.clearRetiredKeywordRules();
        preferences.clearQuarantine();
        StatusNotifier.refresh(context, preferences);
    }

    /**
     * Keeps enforcement true to the desired state without anything staying resident. Persisted
     * so it survives reboot on its own; the boot receiver re-registers it in case the OEM
     * dropped it.
     *
     * <p>This is the backbone, and it also arms the short poll, whose only job is to notice
     * what this one is too slow for.</p>
     */
    static void schedulePeriodicReconcile(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(RECONCILE_JOB_ID,
                new ComponentName(context, PolicyReconcileJobService.class))
                .setPeriodic(RECONCILE_INTERVAL_MS)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build();
        try {
            scheduler.schedule(job);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            Log.e(TAG, "Could not schedule the reconcile job", refused);
        }
        scheduleFastReconcile(context);
    }

    /**
     * The short poll that notices a browser that has just been installed.
     *
     * <p>A new package is the one change the device makes without telling Voward. Measured on
     * this device as device owner, a manifest {@code PACKAGE_ADDED} receiver is never delivered,
     * so there is no install event to react to, and until something reconciles, a browser that
     * cannot be filtered is a browser with no website rules on it at all. The platform floors
     * {@code setPeriodic} at fifteen minutes, which was that whole window; a one-shot job has no
     * floor.</p>
     *
     * <p>It re-arms itself after each run. If that chain ever breaks — the process killed
     * between finishing and rescheduling — the fifteen minute job puts it back, which is why
     * both still exist.</p>
     */
    static void scheduleFastReconcile(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(FAST_RECONCILE_JOB_ID,
                new ComponentName(context, PolicyReconcileJobService.class))
                .setMinimumLatency(FAST_RECONCILE_INTERVAL_MS)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build();
        try {
            scheduler.schedule(job);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            Log.e(TAG, "Could not schedule the fast reconcile job", refused);
        }
    }

    /**
     * An app has been uninstalled: forget it, then reconcile from what is really there.
     *
     * <p>Forgetting first is the point. The mirror is what the reconciler diffs against, so an
     * entry for a package that no longer exists would make the copy installed in its place look
     * like one that is already suspended, and it would never be suspended again.</p>
     */
    static void onPackageRemoved(Context context, String packageName) {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(context);
        if (!preferences.getIsBlockerActive()) return;
        // The survey caches for a minute, and the browser landscape has just changed.
        BrowserPolicy.invalidate();
        // A quarantine holds an app for one decision. Uninstalling it is one, and leaving the
        // entry behind would have every reconcile from here on ask the platform to suspend a
        // package that is not there and be refused. A rule the user wrote is different and is
        // deliberately kept: writing one before installing the app is a supported thing to do.
        preferences.releaseFromQuarantine(packageName);
        new PolicyReconciler(context).forgetPackage(packageName);
        reconcileNow(context);
    }

    private static boolean hasDeadMansSwitchExpired(Context context,
                                                    AppPreferencesManagerSingleton preferences) {
        long elapsed = DeadMansSwitchPolicy.elapsedSinceCheckMs(
                preferences.getDeadMansSwitchWallTimeMs(),
                preferences.getDeadMansSwitchElapsedRealtimeMs(),
                preferences.getDeadMansSwitchBootCount(),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), readBootCount(context));
        return DeadMansSwitchPolicy.isExpired(elapsed, preferences.getDeadMansSwitchDays());
    }

    /**
     * Section 3.2: {@code keyword:} rules match page text and {@code URLBlocklist} matches
     * URLs, so they have no translation and stop being enforced the moment the browser takes
     * over. Recording them is what turns that from a silent loss into something the user is
     * told about.
     */
    private static void recordRetiredKeywordRules(AppPreferencesManagerSingleton preferences) {
        preferences.setRetiredKeywordRules(
                BrowserPolicy.keywordRules(preferences.getRestrictedUrls()));
    }

    private static void recordHealthCheck(Context context,
                                          AppPreferencesManagerSingleton preferences) {
        preferences.setDeadMansSwitchCheck(System.currentTimeMillis(),
                SystemClock.elapsedRealtime(), readBootCount(context));
    }

    private static int readBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            return -1;
        }
    }
}
