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
            checkDecayResponsive();
            forceResetBudget(today);
        }
    }

    /**
     * More responsive temporal decay. 
     * Called on service connect, app switches, and daily resets.
     */
    public void checkDecayResponsive() {
        long now = System.currentTimeMillis();
        long lastForbidden = appPreferencesManager.getLastForbiddenTimestamp();
        long lastDecay = appPreferencesManager.getLastDecayTimestamp();
        
        // Effective cleanliness start is either the last violation OR the last time decay was applied.
        long effectiveSince = Math.max(lastForbidden, lastDecay);
        
        if (effectiveSince > 0) {
            long diffMs = now - effectiveSince;
            long diffHours = diffMs / (1000 * 60 * 60);
            
            if (decayFactorIfClean(diffHours)) {
                appPreferencesManager.setLastDecayTimestamp(now);
            }
        }
    }
    
    public void forceResetBudget(String dateString) {
        long dailyAllowance = appPreferencesManager.getDailyAllowanceUnits();
        long currentRemaining = appPreferencesManager.getRemainingPotentialUnits();
        
        // Cumulative reset: Allowance is added to current balance, but capped at dailyAllowance.
        // This allows carrying over debt (negative balance) while preventing hoarding.
        long newTotal = Math.min(dailyAllowance, currentRemaining + dailyAllowance);
        
        // HIGH-04: Write all reset fields atomically to prevent inconsistent state on
        // process death (previously 5 separate apply()/commit() calls).
        appPreferencesManager.commitResetBatch(newTotal, 0, dateString, 0, 0);
        
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
        // Continuous integral of (fEntry + alpha * t) dt from 0 to seconds
        // = fEntry * seconds + 0.5 * alpha * seconds^2
        long unitCost = Math.round(seconds * fEntry + 0.5 * alpha * seconds * seconds);
        return Math.max(1, unitCost);
    }

    public void depleteBudget(long timeSpentMillis) {
        resetBudgetIfNeeded();

        long dailyForbidden = appPreferencesManager.getDailyForbiddenTimeMs() + timeSpentMillis;
        long dailySessionSum = appPreferencesManager.getDailySessionTimeSumMs() + timeSpentMillis;

        int sessionCount = appPreferencesManager.getDailySessionCount();
        if (sessionCount <= 0) sessionCount = 1;

        // CompulsionIndex: driven by session frequency. More return-visits per day
        // indicates higher compulsion. 10 sessions normalises to cNew = 1.0.
        // (Previous formula mu/T always collapsed to 1/sessionCount regardless of
        // actual time values because dailySessionSum == dailyForbidden.)
        double R = Math.min(0.5, sessionCount * 0.05);

        float cPrev = appPreferencesManager.getCompulsionIndexC();
        float cNew = (float) Math.max(0.0, Math.min(1.0, (R - 0.05) / 0.45));
        float c = 0.8f * cPrev + 0.2f * cNew;

        long unitCost = calculateEscalatedCost(timeSpentMillis);
        long remainingUnits = appPreferencesManager.getRemainingPotentialUnits() - unitCost;

        // Write all depletion fields atomically in a single commit so a process-kill
        // between writes can never leave remainingUnits debited but stats un-updated.
        appPreferencesManager.commitDepletionBatch(
                dailyForbidden, dailySessionSum,
                c, c,
                remainingUnits, System.currentTimeMillis());

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

    public boolean decayFactorIfClean(long hoursSinceLastForbidden) {
        // Use the locked C from the last violation to determine threshold
        float cLocked = appPreferencesManager.getCAtLastForbidden();
        double thresholdH = 24.0 - 18.0 * cLocked;
        
        if (hoursSinceLastForbidden >= thresholdH) {
            float currentFactor = appPreferencesManager.getCostIncrementFactor();
            float decayStep = appPreferencesManager.getDecayStep();
            float currentC = appPreferencesManager.getCompulsionIndexC();
            
            // Reward sustained recovery: more steps if clean for a long time.
            int steps = (int) ((hoursSinceLastForbidden - thresholdH) / 12) + 1;
            steps = Math.min(steps, 5); 
            
            float newFactor = Math.max(1.0f, currentFactor - (decayStep * steps));
            float newC = Math.max(0.0f, currentC - (0.1f * steps));
            
            appPreferencesManager.setCostIncrementFactor(newFactor);
            appPreferencesManager.setCompulsionIndexC(newC);
            
            Log.d(TAG, "Multi-step decay triggered. Steps: " + steps + " | New Factor: " + newFactor + " | New C: " + newC);
            return true;
        }
        return false;
    }
}
