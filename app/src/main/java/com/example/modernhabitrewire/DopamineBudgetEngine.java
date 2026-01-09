package com.example.modernhabitrewire;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Engine handles the "Dopamine Units" (DU) system.
 * 1 DU = 1 second of physical time at a variable multiplier.
 * Modified to use Adaptive Depletion based on session duration statistics.
 */
public class DopamineBudgetEngine {

    private static final String TAG = "DopamineBudgetEngine";
    private final AppPreferencesManagerSingleton appPreferencesManager;

    public DopamineBudgetEngine(Context context) {
        this.appPreferencesManager = AppPreferencesManagerSingleton.getInstance(context);
    }

    /**
     * Checks if a new day has started and resets stats if necessary.
     * Called at the start of major engine operations.
     */
    public void resetBudgetIfNeeded() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String lastResetDate = appPreferencesManager.getLastBudgetResetDate();

        if (!today.equals(lastResetDate)) {
            forceResetBudget(today);
        }
    }
    
    public void forceResetBudget(String dateString) {
        long totalUnits = appPreferencesManager.getDailyAllowanceUnits();
        appPreferencesManager.setRemainingPotentialUnits(totalUnits);
        appPreferencesManager.setDailySessionCount(0);
        appPreferencesManager.setLastBudgetResetDate(dateString);
        
        // Reset adaptive tracked stats daily
        appPreferencesManager.setDailyForbiddenTimeMs(0);
        appPreferencesManager.setDailySessionTimeSumMs(0);
        appPreferencesManager.setCompulsionIndexC(0.0f);
        
        Log.d(TAG, "Full budget reset. Adaptive stats cleared. Potential restored to: " + totalUnits + " DU");
    }

    public void updateRemainingBudgetOnly() {
        long totalUnits = appPreferencesManager.getDailyAllowanceUnits();
        appPreferencesManager.setRemainingPotentialUnits(totalUnits);
        Log.d(TAG, "Potential updated to: " + totalUnits + " DU");
    }

    public void resetAllStats() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        forceResetBudget(today);
        Log.d(TAG, "All stats reset manually.");
    }

    public boolean hasBudget() {
        resetBudgetIfNeeded();
        return appPreferencesManager.getRemainingPotentialUnits() > 0;
    }

    /**
     * Calculates the entry multiplier (F_entry) for the current session.
     */
    public double calculateCurrentMultiplier() {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float f0 = appPreferencesManager.getCostIncrementFactor();
        float c = appPreferencesManager.getCompulsionIndexC();
        
        // Match Day 1 protection logic used in depleteBudget
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        // F_entry formula
        return f0 + (0.5 + 0.5 * c) * sessionCount;
    }

    /**
     * Calculates the instantaneous multiplier at a specific point in the session.
     * M(t) = F_entry + alpha * seconds
     */
    public double calculateInstantaneousMultiplier(long timeSpentMillis) {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float c = appPreferencesManager.getCompulsionIndexC();
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        double fEntry = calculateCurrentMultiplier();
        // alpha = (0.001 + 0.005 * C) * graceMultiplier
        double alpha = (0.001 + 0.005 * c) * appPreferencesManager.getGraceMultiplier();
        double seconds = timeSpentMillis / 1000.0;

        return fEntry + (alpha * seconds);
    }

    /**
     * Calculates the total escalated cost for a given duration without updating state.
     * Used for realtime notification and live enforcement.
     */
    public long calculateEscalatedCost(long timeSpentMillis) {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float c = appPreferencesManager.getCompulsionIndexC();
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        double fEntry = calculateCurrentMultiplier();
        // alpha = (0.001 + 0.005 * C) * graceMultiplier
        double alpha = (0.001 + 0.005 * c) * appPreferencesManager.getGraceMultiplier();
        double seconds = timeSpentMillis / 1000.0;

        // Sum of arithmetic progression: seconds * fEntry + alpha * [seconds * (seconds - 1) / 2]
        long unitCost = Math.round(seconds * fEntry + alpha * (seconds * (seconds - 1) / 2.0));
        return Math.max(1, unitCost);
    }

    /**
     * Depletes budget using adaptive logic and in-session escalation.
     * Assumes it is called ONCE at the end of a forbidden session.
     * @param timeSpentMillis The duration spent on forbidden content in the current session.
     */
    public void depleteBudget(long timeSpentMillis) {
        // 0. Initial state check and reset
        resetBudgetIfNeeded();
        long remainingUnits = appPreferencesManager.getRemainingPotentialUnits();

        // Budget exhaustion rule: Stop further depletion if already at 0
        if (remainingUnits <= 0) {
            Log.d(TAG, "Budget exhausted, engine is now passive.");
            return;
        }

        // 1. Update Tracked Stats
        long dailyForbidden = appPreferencesManager.getDailyForbiddenTimeMs() + timeSpentMillis;
        long dailySessionSum = appPreferencesManager.getDailySessionTimeSumMs() + timeSpentMillis;
        appPreferencesManager.setDailyForbiddenTimeMs(dailyForbidden);
        appPreferencesManager.setDailySessionTimeSumMs(dailySessionSum);

        int sessionCount = appPreferencesManager.getDailySessionCount();
        // Safety: If somehow deplete is called without increment, treat as 1st session
        if (sessionCount <= 0) sessionCount = 1; 

        // 2. Compute Adaptive Compulsion Index (C)
        double mu = (double) dailySessionSum / sessionCount;
        double T = (double) dailyForbidden;
        double R = (T == 0) ? 0 : (mu / T);

        float cPrev = appPreferencesManager.getCompulsionIndexC();
        float cNew = (float) Math.max(0.0, Math.min(1.0, (R - 0.05) / 0.45));
        
        float c = 0.8f * cPrev + 0.2f * cNew;
        // Persist raw C
        appPreferencesManager.setCompulsionIndexC(c);

        // 3. Compute Cost with In-Session Escalation
        long unitCost = calculateEscalatedCost(timeSpentMillis);

        remainingUnits = Math.max(0, remainingUnits - unitCost);
        appPreferencesManager.setRemainingPotentialUnits(remainingUnits);

        // Persist timestamp for recovery logic
        appPreferencesManager.setLastForbiddenTimestamp(System.currentTimeMillis());

        Log.d(TAG, String.format(Locale.US, 
                "Adaptive Deplete: %.1fs | C: %.3f | Cost: %d DU | Rem: %d DU",
                timeSpentMillis / 1000.0, c, unitCost, remainingUnits));
    }

    public long getRemainingBudget() {
        return appPreferencesManager.getRemainingPotentialUnits();
    }

    public void incrementSessionCount() {
        resetBudgetIfNeeded();
        if (appPreferencesManager.getRemainingPotentialUnits() <= 0) {
            Log.d(TAG, "No budget remaining, skipping session count increment.");
            return;
        }
        int currentCount = appPreferencesManager.getDailySessionCount();
        appPreferencesManager.setDailySessionCount(currentCount + 1);
    }

    /**
     * Recovery logic: decays the base factor if the user has been "clean".
     */
    public void decayFactorIfClean(long hoursSinceLastForbidden) {
        float c = appPreferencesManager.getCompulsionIndexC();
        double thresholdH = 24.0 - 18.0 * c;
        
        if (hoursSinceLastForbidden >= thresholdH) {
            float currentFactor = appPreferencesManager.getCostIncrementFactor();
            float decayStep = appPreferencesManager.getDecayStep();
            appPreferencesManager.setCostIncrementFactor(Math.max(1.0f, currentFactor - decayStep));
            Log.d(TAG, "Recovery: Clean for " + hoursSinceLastForbidden + "h. Factor decayed by " + decayStep + " to " + appPreferencesManager.getCostIncrementFactor());
        }
    }
}
