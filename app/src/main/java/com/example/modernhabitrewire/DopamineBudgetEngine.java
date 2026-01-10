package com.example.modernhabitrewire;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Engine handles the "Dopamine Units" (DU) system.
 * 1 DU = 1 second of physical time at a variable multiplier.
 * Modified to support Cumulative Daily Resets. Mathematical overdraw penalties removed
 * in favor of structural friction in the Firewall.
 */
public class DopamineBudgetEngine {

    private static final String TAG = "DopamineBudgetEngine";
    private final AppPreferencesManagerSingleton appPreferencesManager;

    public DopamineBudgetEngine(Context context) {
        this.appPreferencesManager = AppPreferencesManagerSingleton.getInstance(context);
    }

    public void resetBudgetIfNeeded() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String lastResetDate = appPreferencesManager.getLastBudgetResetDate();

        if (!today.equals(lastResetDate)) {
            forceResetBudget(today);
        }
    }
    
    public void forceResetBudget(String dateString) {
        long dailyAllowance = appPreferencesManager.getDailyAllowanceUnits();
        long currentRemaining = appPreferencesManager.getRemainingPotentialUnits();
        
        // Cumulative reset: Allowance is added to the current balance (carry-over debt)
        long newTotal = currentRemaining + dailyAllowance;
        
        appPreferencesManager.setRemainingPotentialUnits(newTotal);
        appPreferencesManager.setDailySessionCount(0);
        appPreferencesManager.setLastBudgetResetDate(dateString);
        
        appPreferencesManager.setDailyForbiddenTimeMs(0);
        appPreferencesManager.setDailySessionTimeSumMs(0);
        appPreferencesManager.setCompulsionIndexC(0.0f);
        
        Log.d(TAG, "Cumulative budget reset. New Potential: " + newTotal + " DU");
    }

    public void updateRemainingBudgetOnly() {
        long totalUnits = appPreferencesManager.getDailyAllowanceUnits();
        appPreferencesManager.setRemainingPotentialUnits(totalUnits);
    }

    public void resetAllStats() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        appPreferencesManager.setRemainingPotentialUnits(0);
        forceResetBudget(today);
    }

    /**
     * Checks if the user currently has a positive unit balance.
     * Note: Negative balance is allowed, but triggers enforcement friction.
     */
    public boolean hasPositiveBudget() {
        resetBudgetIfNeeded();
        return appPreferencesManager.getRemainingPotentialUnits() > 0;
    }

    public double calculateCurrentMultiplier() {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float f0 = appPreferencesManager.getCostIncrementFactor();
        float c = appPreferencesManager.getCompulsionIndexC();
        
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        // Default behavior (no overdraw math penalties)
        return f0 + (0.5 + 0.5 * c) * sessionCount;
    }

    public int calculateWaitSeconds() {
        double baseWaitSeconds = appPreferencesManager.getBaseWaitTimeSeconds();
        double factor = calculateCurrentMultiplier();

        // Default behavior: Logarithmic scaling based on factor
        double waitSeconds = baseWaitSeconds * (1.0 + Math.log(factor) / Math.log(2.0));

        return (int) Math.round(waitSeconds);
    }

    public double calculateInstantaneousMultiplier(long timeSpentMillis) {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float c = appPreferencesManager.getCompulsionIndexC();
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        double fEntry = calculateCurrentMultiplier();
        double alpha = (0.001 + 0.005 * c) * appPreferencesManager.getGraceMultiplier();
        
        double seconds = timeSpentMillis / 1000.0;
        return fEntry + (alpha * seconds);
    }

    public long calculateEscalatedCost(long timeSpentMillis) {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float c = appPreferencesManager.getCompulsionIndexC();
        if (sessionCount < 3) {
            c = Math.min(c, 0.3f);
        }

        double fEntry = calculateCurrentMultiplier();
        double alpha = (0.001 + 0.005 * c) * appPreferencesManager.getGraceMultiplier();
        
        double seconds = timeSpentMillis / 1000.0;
        long unitCost = Math.round(seconds * fEntry + alpha * (seconds * (seconds - 1) / 2.0));
        return Math.max(1, unitCost);
    }

    public void depleteBudget(long timeSpentMillis) {
        resetBudgetIfNeeded();
        
        long dailyForbidden = appPreferencesManager.getDailyForbiddenTimeMs() + timeSpentMillis;
        long dailySessionSum = appPreferencesManager.getDailySessionTimeSumMs() + timeSpentMillis;
        appPreferencesManager.setDailyForbiddenTimeMs(dailyForbidden);
        appPreferencesManager.setDailySessionTimeSumMs(dailySessionSum);

        int sessionCount = appPreferencesManager.getDailySessionCount();
        if (sessionCount <= 0) sessionCount = 1; 

        double mu = (double) dailySessionSum / sessionCount;
        double T = (double) dailyForbidden;
        double R = (T == 0) ? 0 : (mu / T);

        float cPrev = appPreferencesManager.getCompulsionIndexC();
        float cNew = (float) Math.max(0.0, Math.min(1.0, (R - 0.05) / 0.45));
        float c = 0.8f * cPrev + 0.2f * cNew;
        appPreferencesManager.setCompulsionIndexC(c);

        long unitCost = calculateEscalatedCost(timeSpentMillis);

        long remainingUnits = appPreferencesManager.getRemainingPotentialUnits();
        remainingUnits -= unitCost;
        appPreferencesManager.setRemainingPotentialUnits(remainingUnits);

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
        int currentCount = appPreferencesManager.getDailySessionCount();
        appPreferencesManager.setDailySessionCount(currentCount + 1);
    }

    public void decayFactorIfClean(long hoursSinceLastForbidden) {
        float c = appPreferencesManager.getCompulsionIndexC();
        double thresholdH = 24.0 - 18.0 * c;
        
        if (hoursSinceLastForbidden >= thresholdH) {
            float currentFactor = appPreferencesManager.getCostIncrementFactor();
            float decayStep = appPreferencesManager.getDecayStep();
            appPreferencesManager.setCostIncrementFactor(Math.max(1.0f, currentFactor - decayStep));
        }
    }
}
