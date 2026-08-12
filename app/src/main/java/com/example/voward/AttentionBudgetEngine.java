package com.example.voward;

import android.content.Context;
import android.util.Log;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Coordinates the transparent attention budget. One stored unit is exactly one second of
 * approved restricted use; the engine does not infer any psychological or biological state.
 */
public class AttentionBudgetEngine {

    private static final String TAG = "AttentionBudgetEngine";
    private final AppPreferencesManagerSingleton preferences;

    public AttentionBudgetEngine(Context context) {
        preferences = AppPreferencesManagerSingleton.getInstance(context);
    }

    public void resetBudgetIfNeeded() {
        LocalDate today = LocalDate.now();
        long todayEpochDay = today.toEpochDay();
        long lastEpochDay = getMigratedLastResetEpochDay(today);

        // Never move the marker backwards. This prevents duplicate grants after a clock rollback.
        if (todayEpochDay > lastEpochDay) {
            applyDailyAllowance(today, todayEpochDay - lastEpochDay);
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

    private void applyDailyAllowance(LocalDate date, long elapsedDays) {
        long dailyAllowance = preferences.getDailyAllowanceSeconds();
        long currentRemaining = preferences.getRemainingBudgetSeconds();
        long newTotal = BudgetMath.addDailyAllowancesBounded(currentRemaining, dailyAllowance,
                elapsedDays, preferences.getCarryoverCapDays());
        preferences.applyResetBatch(newTotal, 0, date.toString(), date.toEpochDay());

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
    }
}
