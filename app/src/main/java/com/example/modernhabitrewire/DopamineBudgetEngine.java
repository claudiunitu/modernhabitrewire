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

    public void resetBudgetIfNeeded() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String lastResetDate = appPreferencesManager.getLastBudgetResetDate();

        if (!today.equals(lastResetDate)) {
            long totalBudgetMs = appPreferencesManager.getDailyBudgetMinutes() * 60 * 1000L;
            appPreferencesManager.setRemainingDopamineBudgetMs(totalBudgetMs);
            appPreferencesManager.setDailySessionCount(0);
            appPreferencesManager.setLastBudgetResetDate(today);
            Log.d(TAG, "Budget reset for the new day: " + appPreferencesManager.getDailyBudgetMinutes() + " minutes.");
        }
    }

    public boolean hasBudget() {
        return appPreferencesManager.getRemainingDopamineBudgetMs() > 0;
    }

    public double calculateCurrentMultiplier() {
        // Physics: 1.0 initial + (sessions * factor)
        int sessionCount = appPreferencesManager.getDailySessionCount();
        float factor = appPreferencesManager.getCostIncrementFactor();
        
        return 1.0 + (sessionCount * factor);
    }

    public void depleteBudget(long timeSpentMillis) {
        double multiplier = calculateCurrentMultiplier();
        long actualCost = (long) (timeSpentMillis * multiplier);
        
        long remainingBudget = appPreferencesManager.getRemainingDopamineBudgetMs();
        appPreferencesManager.setRemainingDopamineBudgetMs(Math.max(0, remainingBudget - actualCost));
        
        Log.d(TAG, "Depleted: " + (timeSpentMillis/1000) + "s x " + multiplier + " = " + (actualCost/1000) + "s. Remaining: " + (appPreferencesManager.getRemainingDopamineBudgetMs()/1000) + "s");
    }

    public long getRemainingBudget() {
        return appPreferencesManager.getRemainingDopamineBudgetMs();
    }
    
    public void incrementSessionCount() {
        int currentCount = appPreferencesManager.getDailySessionCount();
        appPreferencesManager.setDailySessionCount(currentCount + 1);
    }
}
