package com.example.modernhabitrewire;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Engine handles the "Dopamine Units" (DU) system.
 * 1 DU = 1 second of physical time at a 1.0x multiplier.
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
        long totalUnits = appPreferencesManager.getDailyAllowanceUnits();
        appPreferencesManager.setRemainingPotentialUnits(totalUnits);
        appPreferencesManager.setDailySessionCount(0);
        appPreferencesManager.setLastBudgetResetDate(dateString);
        Log.d(TAG, "Full budget reset. Potential restored to: " + totalUnits + " DU");
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
        return getRemainingBudget() > 0;
    }

    public double calculateCurrentMultiplier() {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float factor = appPreferencesManager.getCostIncrementFactor();
        return sessionCount * factor;
    }

    public void depleteBudget(long timeSpentMillis) {
        resetBudgetIfNeeded();
        double multiplier = calculateCurrentMultiplier();
        double secondsSpent = timeSpentMillis / 1000.0;
        long unitCost = Math.round(secondsSpent * multiplier);
        
        long remainingUnits = getRemainingBudget();
        appPreferencesManager.setRemainingPotentialUnits(Math.max(0, remainingUnits - unitCost));
        
        Log.d(TAG, "Spent: " + String.format("%.1fs", secondsSpent) + " x " + multiplier + "x = " + unitCost + " DU. Remaining: " + getRemainingBudget() + " DU");
    }

    public long getRemainingBudget() {
        resetBudgetIfNeeded();
        return appPreferencesManager.getRemainingPotentialUnits();
    }
    
    public void incrementSessionCount() {
        resetBudgetIfNeeded();
        int currentCount = appPreferencesManager.getDailySessionCount();
        appPreferencesManager.setDailySessionCount(currentCount + 1);
    }
}
