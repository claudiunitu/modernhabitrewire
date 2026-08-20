package com.example.voward;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AttentionBudgetEngineTest {
    private Application application;
    private AppPreferencesManagerSingleton preferences;
    private AttentionBudgetEngine engine;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences("portable_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);
        engine = new AttentionBudgetEngine(application);
    }

    @Test
    public void firstUseGrantsOneDayAndPersistsTodaysMarker() {
        preferences.setDailyAllowanceSeconds(100);
        preferences.setRemainingBudgetSeconds(20);
        assertEquals(Long.MIN_VALUE, preferences.getLastBudgetResetEpochDay());

        engine.resetBudgetIfNeeded();

        LocalDate today = LocalDate.now();
        assertEquals(100, preferences.getRemainingBudgetSeconds());
        assertEquals(today.toEpochDay(), preferences.getLastBudgetResetEpochDay());
        assertEquals(today.toString(), preferences.getLastBudgetResetDate());
        assertEquals(0, preferences.getDailySessionCount());
    }

    @Test
    public void elapsedDaysAreGrantedOnceAndClockRollbackCannotDuplicateAllowance() {
        LocalDate today = LocalDate.now();
        preferences.setDailyAllowanceSeconds(100);
        preferences.setCarryoverCapDays(1);
        preferences.setRemainingBudgetSeconds(0);
        preferences.setLastBudgetResetEpochDay(today.minusDays(3).toEpochDay());
        engine.resetBudgetIfNeeded();
        assertEquals(100, preferences.getRemainingBudgetSeconds());

        preferences.setRemainingBudgetSeconds(25);
        preferences.setLastBudgetResetEpochDay(today.plusDays(1).toEpochDay());
        engine.resetBudgetIfNeeded();
        assertEquals(25, preferences.getRemainingBudgetSeconds());
        assertEquals(today.plusDays(1).toEpochDay(), preferences.getLastBudgetResetEpochDay());
    }

    @Test
    public void legacyDateMarkerAndMalformedDateAreMigrated() {
        LocalDate today = LocalDate.now();
        preferences.setDailyAllowanceSeconds(100);
        preferences.setLastBudgetResetDate(today.minusDays(1).toString());
        engine.resetBudgetIfNeeded();
        assertEquals(today.toEpochDay(), preferences.getLastBudgetResetEpochDay());
        assertEquals(100, preferences.getRemainingBudgetSeconds());

        application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE).edit()
                .remove("last_budget_reset_epoch_day")
                .putString("last_budget_reset_date", "not-a-date")
                .putLong("remaining_budget_seconds", 0)
                .commit();
        engine.resetBudgetIfNeeded();
        assertEquals(100, preferences.getRemainingBudgetSeconds());
        assertEquals(today.toEpochDay(), preferences.getLastBudgetResetEpochDay());
    }

    @Test
    public void allowanceChangesAndNormalizationRespectCurrentCarryCap() {
        preferences.setCarryoverCapDays(1);
        preferences.setRemainingBudgetSeconds(50);
        engine.updateRemainingBudgetForAllowanceChange(100, 200);
        assertEquals(150, preferences.getRemainingBudgetSeconds());
        engine.updateRemainingBudgetForAllowanceChange(200, 100);
        assertEquals(50, preferences.getRemainingBudgetSeconds());

        preferences.setDailyAllowanceSeconds(100);
        preferences.setRemainingBudgetSeconds(500);
        engine.normalizeBalanceToCurrentLimits();
        assertEquals(100, preferences.getRemainingBudgetSeconds());
    }

    @Test
    public void quotingUsageWaitAndSessionCountDelegateToTransparentMath() {
        LocalDate today = LocalDate.now();
        preferences.setLastBudgetResetEpochDay(today.toEpochDay());
        preferences.setRemainingBudgetSeconds(100);
        preferences.setBaseWaitTimeSeconds(30);
        preferences.setReentryGrowth(1);
        preferences.setDailySessionCount(1);

        assertTrue(engine.hasPositiveBudget());
        assertEquals(60, engine.calculateWaitSeconds());
        assertEquals(5, engine.calculateUsageSeconds(5_999));
        assertEquals(60, engine.quoteSessionSeconds(60));
        assertEquals(100, engine.quoteSessionSeconds(200));

        engine.recordUsageDelta(5_999, 5);
        assertEquals(95, engine.getRemainingBudget());
        assertEquals(5_999, preferences.getDailyRestrictedTimeMs());
        engine.incrementSessionCount();
        assertEquals(2, preferences.getDailySessionCount());

        preferences.setRemainingBudgetSeconds(0);
        assertFalse(engine.hasPositiveBudget());
    }

    @Test
    public void resetTodayStatisticsPreservesBudgetAndResetMarker() {
        LocalDate marker = LocalDate.now().minusDays(2);
        preferences.setDailyAllowanceSeconds(300);
        preferences.setRemainingBudgetSeconds(10);
        preferences.setLastBudgetResetEpochDay(marker.toEpochDay());
        preferences.setDailySessionCount(4);
        preferences.incrementFrictionAborted();
        engine.resetTodayStatistics();
        assertEquals(10, preferences.getRemainingBudgetSeconds());
        assertEquals(0, preferences.getDailySessionCount());
        assertEquals(0, preferences.getFrictionAbortedCount());
        assertEquals(marker.toEpochDay(), preferences.getLastBudgetResetEpochDay());
    }

    private static void resetSingleton() throws Exception {
        Field field = AppPreferencesManagerSingleton.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
