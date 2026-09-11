package com.example.voward;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Coordinates the transparent attention budget. One stored unit is exactly one second of
 * approved restricted use; the engine does not infer any psychological or biological state.
 */
public class AttentionBudgetEngine {

    private static final String TAG = "AttentionBudgetEngine";
    /** Shared with the deactivation engine so both judge the clock by the same margin. */
    private static final long CLOCK_SHIFT_TOLERANCE_MS =
            DeactivationPolicyEngine.CLOCK_SHIFT_TOLERANCE_MS;
    /** Granted when monotonic time cannot vouch for the wall clock, such as after a reboot. */
    private static final long UNVERIFIED_MAX_DAYS = 1;

    private final AppPreferencesManagerSingleton preferences;
    private final Context appContext;

    public AttentionBudgetEngine(Context context) {
        appContext = context.getApplicationContext();
        preferences = AppPreferencesManagerSingleton.getInstance(context);
    }

    public void resetBudgetIfNeeded() {
        resetBudgetIfNeeded(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                readBootCount());
    }

    /** Visible for tests: the three clock readings a daily grant is judged against. */
    void resetBudgetIfNeeded(long wallTimeMs, long elapsedRealtimeMs, int bootCount) {
        LocalDate today = Instant.ofEpochMilli(wallTimeMs)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        long todayEpochDay = today.toEpochDay();
        long lastEpochDay = getMigratedLastResetEpochDay(today);

        // Never move the marker backwards. This prevents duplicate grants after a clock rollback.
        if (todayEpochDay <= lastEpochDay) return;

        long grantedDays = grantableDays(todayEpochDay - lastEpochDay, wallTimeMs,
                elapsedRealtimeMs, bootCount);
        if (grantedDays <= 0) {
            Log.w(TAG, "Daily allowance withheld: wall clock advanced without monotonic progress");
            return;
        }
        applyDailyAllowance(today, grantedDays, wallTimeMs, elapsedRealtimeMs, bootCount);
    }

    /**
     * How many of the wall-clock days since the last grant monotonic time can account for.
     * Rolling the clock forward moves the calendar date without moving
     * {@link SystemClock#elapsedRealtime()}, and that gap is what this refuses to pay for.
     */
    private long grantableDays(long wallDays, long wallTimeMs, long elapsedRealtimeMs,
                               int bootCount) {
        long markerWallMs = preferences.getBudgetClockWallTimeMs();
        long markerElapsedMs = preferences.getBudgetClockElapsedRealtimeMs();
        int markerBootCount = preferences.getBudgetClockBootCount();
        if (markerWallMs == Long.MIN_VALUE || markerElapsedMs == Long.MIN_VALUE) {
            return Math.min(wallDays, UNVERIFIED_MAX_DAYS);
        }

        long realProgress = BudgetMath.addSignedDelta(elapsedRealtimeMs, -markerElapsedMs);
        boolean rebooted = realProgress < 0
                || (bootCount >= 0 && markerBootCount >= 0 && bootCount != markerBootCount);
        if (rebooted) {
            // Monotonic time restarts at boot, so the powered-off period is unmeasurable.
            // One day boundary is plausible; a week of them is not.
            return Math.min(wallDays, UNVERIFIED_MAX_DAYS);
        }

        long wallProgress = BudgetMath.addSignedDelta(wallTimeMs, -markerWallMs);
        long unexplainedForwardMs = BudgetMath.addSignedDelta(wallProgress, -realProgress);
        return unexplainedForwardMs > CLOCK_SHIFT_TOLERANCE_MS ? 0 : wallDays;
    }

    private int readBootCount() {
        try {
            return Settings.Global.getInt(appContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            return -1;
        }
    }

    private long getMigratedLastResetEpochDay(LocalDate today) {
        long stored = preferences.getLastBudgetResetEpochDay();
        if (stored != Long.MIN_VALUE) return stored;
        String date = preferences.getLastBudgetResetDate();
        if (!date.isEmpty()) {
            try {
                stored = LocalDate.parse(date).toEpochDay();
            } catch (DateTimeParseException ignored) {
                stored = today.minusDays(1).toEpochDay();
            }
        } else {
            stored = today.minusDays(1).toEpochDay();
        }
        preferences.setLastBudgetResetEpochDay(stored);
        return stored;
    }

    private void applyDailyAllowance(LocalDate date, long elapsedDays, long wallTimeMs,
                                     long elapsedRealtimeMs, int bootCount) {
        long dailyAllowance = preferences.getDailyAllowanceSeconds();
        long currentRemaining = preferences.getRemainingBudgetSeconds();
        long newTotal = BudgetMath.addDailyAllowancesBounded(currentRemaining, dailyAllowance,
                elapsedDays, preferences.getCarryoverCapDays());
        preferences.applyResetBatch(newTotal, 0, date.toString(), date.toEpochDay());
        preferences.setBudgetClockMarker(wallTimeMs, elapsedRealtimeMs, bootCount);

        Log.d(TAG, "Bounded daily allowance applied; remaining seconds=" + newTotal);
    }

    public void updateRemainingBudgetForAllowanceChange(int oldAllowance, int newAllowance) {
        long delta = (long) newAllowance - oldAllowance;
        long adjusted = BudgetMath.addSignedDelta(preferences.getRemainingBudgetSeconds(), delta);
        preferences.setRemainingBudgetSeconds(BudgetMath.addDailyAllowancesBounded(
                adjusted, newAllowance, 0, preferences.getCarryoverCapDays()));
    }

    public void normalizeBalanceToCurrentLimits() {
        preferences.setRemainingBudgetSeconds(BudgetMath.addDailyAllowancesBounded(
                preferences.getRemainingBudgetSeconds(), preferences.getDailyAllowanceSeconds(), 0,
                preferences.getCarryoverCapDays()));
    }

    public void resetTodayStatistics() {
        preferences.resetTodayStatistics();
    }

    public boolean hasPositiveBudget() {
        resetBudgetIfNeeded();
        return preferences.getRemainingBudgetSeconds() > 0;
    }

    public int calculateWaitSeconds() {
        return BudgetMath.calculateReentryWaitSeconds(
                preferences.getBaseWaitTimeSeconds(), preferences.getReentryGrowth(),
                preferences.getDailySessionCount());
    }

    public long calculateUsageSeconds(long timeSpentMillis) {
        return BudgetMath.elapsedCostSeconds(timeSpentMillis);
    }

    public long quoteSessionSeconds(int requestedSeconds) {
        resetBudgetIfNeeded();
        return BudgetMath.quoteSessionSeconds(getRemainingBudget(), requestedSeconds);
    }

    public void recordUsageDelta(long timeSpentMillis, long usedSeconds) {
        resetBudgetIfNeeded();
        preferences.applyUsageDelta(Math.max(0, timeSpentMillis), Math.max(0, usedSeconds));
    }

    public long getRemainingBudget() {
        return preferences.getRemainingBudgetSeconds();
    }

    public void incrementSessionCount() {
        resetBudgetIfNeeded();
        preferences.setDailySessionCount(preferences.getDailySessionCount() + 1);
        preferences.incrementSessionStartHour(LocalTime.now().getHour());
    }
}
