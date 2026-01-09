package com.example.modernhabitrewire;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DopamineBudgetEngine {

    private static final String TAG = "DopamineBudgetEngine";
    private final AppPreferencesManagerSingleton appPreferencesManager;

    public DopamineBudgetEngine(Context context) {
        this.appPreferencesManager = AppPreferencesManagerSingleton.getInstance(context);
    }

    /**
     * Resets the budget for the day. 
     * IMPORTANT: This should be called before checking hasBudget() or getting remaining budget.
     */
    public void resetBudgetIfNeeded() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String lastResetDate = appPreferencesManager.getLastBudgetResetDate();

        if (!today.equals(lastResetDate)) {
            forceResetBudget(today);
        }
    }
    
    public void forceResetBudget(String dateString) {
        long totalBudgetMs = appPreferencesManager.getDailyBudgetMinutes() * 60 * 1000L;
        appPreferencesManager.setRemainingDopamineBudgetMs(totalBudgetMs);
        appPreferencesManager.setDailySessionCount(0);
        appPreferencesManager.setLastBudgetResetDate(dateString);
        Log.d(TAG, "Full budget reset for: " + dateString);
    }

    /**
     * Specifically used when the user edits the Daily Budget in settings.
     * Only updates the remaining time without resetting session counts or dates.
     */
    public void updateRemainingBudgetOnly() {
        long totalBudgetMs = appPreferencesManager.getDailyBudgetMinutes() * 60 * 1000L;
        appPreferencesManager.setRemainingDopamineBudgetMs(totalBudgetMs);
        Log.d(TAG, "Remaining budget updated to: " + appPreferencesManager.getDailyBudgetMinutes() + " minutes.");
    }

    /**
     * Completely resets all session stats and budget for the current day.
     * Useful for debugging.
     */
    public void resetAllStats() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        forceResetBudget(today);
        Log.d(TAG, "All stats reset manually.");
    }

    public boolean hasBudget() {
        resetBudgetIfNeeded();
        return appPreferencesManager.getRemainingDopamineBudgetMs() > 0;
    }

    public double calculateCurrentMultiplier() {
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float factor = appPreferencesManager.getCostIncrementFactor();
        return 1.0 + (sessionCount * factor);
    }

    public void depleteBudget(long timeSpentMillis) {
        resetBudgetIfNeeded();
        double multiplier = calculateCurrentMultiplier();
        long actualCost = (long) (timeSpentMillis * multiplier);
        
        long remainingBudget = appPreferencesManager.getRemainingDopamineBudgetMs();
        appPreferencesManager.setRemainingDopamineBudgetMs(Math.max(0, remainingBudget - actualCost));
        
        Log.d(TAG, "Depleted: " + (timeSpentMillis/1000) + "s x " + multiplier + " = " + (actualCost/1000) + "s. Remaining: " + (appPreferencesManager.getRemainingDopamineBudgetMs()/1000) + "s");
    }

    public long getRemainingBudget() {
        resetBudgetIfNeeded();
        return appPreferencesManager.getRemainingDopamineBudgetMs();
    }
    
    public void incrementSessionCount() {
        resetBudgetIfNeeded();
        int currentCount = appPreferencesManager.getDailySessionCount();
        appPreferencesManager.setDailySessionCount(currentCount + 1);
    }
}
