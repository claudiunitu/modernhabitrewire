package com.example.modernhabitrewire;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class AppPreferencesManagerSingleton {

    private static AppPreferencesManagerSingleton _instance = null;
    private static final String PREF_NAME = "global_preferences";
    private static final String KEY_FORBIDDEN_URL_LIST = "forbidden_url_list";
    private static final String KEY_EXTRACTIVE_APP_LIST = "extractive_app_list";
    private static final String KEY_CONSTRUCTIVE_APP_LIST = "constructive_app_list";
    private static final String KEY_NEUTRAL_APP_LIST = "neutral_app_list";
    private static final String KEY_IS_BLOCKER_ACTIVE = "is_blocker_active";
    
    private static final String KEY_DAILY_ALLOWANCE_UNITS = "daily_allowance_units";
    private static final String KEY_REMAINING_POTENTIAL_UNITS = "remaining_potential_units";
    private static final String KEY_LAST_BUDGET_RESET_DATE = "last_budget_reset_date";
    private static final String KEY_TEMP_ALLOW_APP_LAUNCH = "temp_allow_app_launch";
    private static final String KEY_LAST_INTERCEPTED_APP = "last_intercepted_app";
    private static final String KEY_LAST_INTERCEPTED_URL = "last_intercepted_url";
    private static final String KEY_DAILY_SESSION_COUNT = "daily_session_count";
    private static final String KEY_BASE_WAIT_TIME_SECONDS = "base_wait_time_seconds";
    private static final String KEY_COST_INCREMENT_FACTOR = "cost_increment_factor";
    private static final String KEY_LAUNCH_FRICTION_ENABLED = "launch_friction_enabled";

    private static final String DEACTIVATION_KEY = "deactivation_key";
    private static final String BYPASS_SWITCH_VALUE = "bypass_switch_value";
    private static final String FORBID_SETTINGS_SWITCH_VALUE = "forbid_settings_switch_value";

    private static final String KEY_DAILY_FORBIDDEN_TIME_MS = "daily_forbidden_time_ms";
    private static final String KEY_DAILY_SESSION_TIME_SUM_MS = "daily_session_time_sum_ms";
    private static final String KEY_COMPULSION_INDEX_C = "compulsion_index_c";
    private static final String KEY_C_AT_LAST_FORBIDDEN = "c_at_last_forbidden";
    private static final String KEY_LAST_FORBIDDEN_TIMESTAMP = "last_forbidden_timestamp";

    private static final String KEY_DECAY_STEP = "decay_step";
    private static final String KEY_GRACE_MULTIPLIER = "grace_multiplier";
    private static final String KEY_LAST_DECAY_TIMESTAMP = "last_decay_timestamp";

    // Hostility Metrics
    private static final String KEY_METRIC_FRICTION_SHOWN = "metric_friction_shown";
    private static final String KEY_METRIC_FRICTION_ENDURED = "metric_friction_endured";
    private static final String KEY_METRIC_FRICTION_ABORTED = "metric_friction_aborted";
    private static final String KEY_METRIC_RETRY_LATENCY_SUM = "metric_retry_latency_sum";
    private static final String KEY_METRIC_RETRY_COUNT = "metric_retry_count";

    private SharedPreferences prefs;

    private AppPreferencesManagerSingleton(Context context) {
        // Use application context to avoid leaking Activity/Service contexts
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppPreferencesManagerSingleton getInstance(Context context) {
        if (_instance == null) {
            _instance = new AppPreferencesManagerSingleton(context);
        }
        return _instance;
    }

    public void setForbidSettingsSwitchValue(Boolean flag){
        prefs.edit().putBoolean(FORBID_SETTINGS_SWITCH_VALUE, flag).apply();
    }

    public Boolean getForbidSettingsSwitchValue(){
        return prefs.getBoolean(FORBID_SETTINGS_SWITCH_VALUE, false);
    }

    public void setIsBlockerActive(Boolean flag) {
        prefs.edit().putBoolean(KEY_IS_BLOCKER_ACTIVE, flag).apply();
    }

    public Boolean getIsBlockerActive() {
        return prefs.getBoolean(KEY_IS_BLOCKER_ACTIVE, false);
    }

    public void setBypassSwitchValue(Boolean flag){
        prefs.edit().putBoolean(BYPASS_SWITCH_VALUE, flag).apply();
    }

    public Boolean getBypassSwitchValue(){
        return prefs.getBoolean(BYPASS_SWITCH_VALUE, false);
    }

    private static String sha256(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present on all Android versions
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Store the key as a SHA-256 hash so the raw value is never persisted. */
    public void setDeactivationKey(String key) {
        prefs.edit().putString(DEACTIVATION_KEY, sha256(key)).apply();
    }

    /** Returns true if the stored hash is empty (no key has been set). */
    public String getDeactivationKey() {
        return prefs.getString(DEACTIVATION_KEY, "");
    }

    /** Compare a raw input against the stored hash without exposing the key. */
    public boolean verifyDeactivationKey(String input) {
        String stored = getDeactivationKey();
        if (stored.isEmpty()) return false;
        return stored.equals(sha256(input));
    }

    public List<String> getForbiddenUrls() {
        String raw = prefs.getString(KEY_FORBIDDEN_URL_LIST, "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(raw.split(",")));
    }

    public void setForbiddenUrls(List<String> urls) {
        prefs.edit().putString(KEY_FORBIDDEN_URL_LIST, String.join(",", urls)).apply();
    }

    public void addForbiddenUrl(String url) {
        List<String> urls = getForbiddenUrls();
        if (!urls.contains(url)) {
            urls.add(url);
            setForbiddenUrls(urls);
        }
    }

    public void removeUrl(String url) {
        List<String> urls = getForbiddenUrls();
        urls.remove(url);
        setForbiddenUrls(urls);
    }

    public List<String> getExtractiveAppsPackages() {
        String raw = prefs.getString(KEY_EXTRACTIVE_APP_LIST, "com.google.android.youtube");
        return new ArrayList<>(List.of(raw.split(",")));
    }

    public void setExtractiveApps(List<String> apps) {
        prefs.edit().putString(KEY_EXTRACTIVE_APP_LIST, String.join(",", apps)).apply();
    }

    public void addExtractiveAppPackage(String appPackage) {
        List<String> apps = getExtractiveAppsPackages();
        if (!apps.contains(appPackage)) {
            apps.add(appPackage);
            setExtractiveApps(apps);
        }
    }

    public void removeExtractiveAppPackage(String appPackage) {
        List<String> apps = getExtractiveAppsPackages();
        apps.remove(appPackage);
        setExtractiveApps(apps);
    }

    public int getDailyAllowanceUnits() {
        return prefs.getInt(KEY_DAILY_ALLOWANCE_UNITS, 1800);
    }

    public void setDailyAllowanceUnits(int units) {
        prefs.edit().putInt(KEY_DAILY_ALLOWANCE_UNITS, units).apply();
    }

    public long getRemainingPotentialUnits() {
        return prefs.getLong(KEY_REMAINING_POTENTIAL_UNITS, 0);
    }

    public void setRemainingPotentialUnits(long units) {
        // Critical for DecisionGate synchronization
        prefs.edit().putLong(KEY_REMAINING_POTENTIAL_UNITS, units).commit();
    }

    public String getLastBudgetResetDate() {
        return prefs.getString(KEY_LAST_BUDGET_RESET_DATE, "");
    }

    public void setLastBudgetResetDate(String date) {
        prefs.edit().putString(KEY_LAST_BUDGET_RESET_DATE, date).apply();
    }

    public boolean getTempAllowAppLaunch() {
        return prefs.getBoolean(KEY_TEMP_ALLOW_APP_LAUNCH, false);
    }

    public void setTempAllowAppLaunch(boolean allowed) {
        prefs.edit().putBoolean(KEY_TEMP_ALLOW_APP_LAUNCH, allowed).commit();
    }

    public String getLastInterceptedApp() {
        return prefs.getString(KEY_LAST_INTERCEPTED_APP, "");
    }

    public void setLastInterceptedApp(String packageName) {
        // Critical for DecisionGate synchronization
        prefs.edit().putString(KEY_LAST_INTERCEPTED_APP, packageName).commit();
    }

    public String getLastInterceptedUrl() {
        return prefs.getString(KEY_LAST_INTERCEPTED_URL, "");
    }

    public void setLastInterceptedUrl(String url) {
        // Critical for DecisionGate synchronization
        prefs.edit().putString(KEY_LAST_INTERCEPTED_URL, url).commit();
    }

    public int getDailySessionCount() {
        return prefs.getInt(KEY_DAILY_SESSION_COUNT, 0);
    }

    public void setDailySessionCount(int count) {
        prefs.edit().putInt(KEY_DAILY_SESSION_COUNT, count).commit();
    }

    public int getBaseWaitTimeSeconds() {
        return prefs.getInt(KEY_BASE_WAIT_TIME_SECONDS, 30);
    }

    public void setBaseWaitTimeSeconds(int seconds) {
        prefs.edit().putInt(KEY_BASE_WAIT_TIME_SECONDS, Math.max(1, seconds)).apply();
    }

    public float getCostIncrementFactor() {
        return prefs.getFloat(KEY_COST_INCREMENT_FACTOR, 1.0f);
    }

    public void setCostIncrementFactor(float factor) {
        prefs.edit().putFloat(KEY_COST_INCREMENT_FACTOR, Math.max(1.0f, factor)).apply();
    }

    public boolean getLaunchFrictionEnabled() {
        return prefs.getBoolean(KEY_LAUNCH_FRICTION_ENABLED, true);
    }

    public void setLaunchFrictionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LAUNCH_FRICTION_ENABLED, enabled).apply();
    }

    public long getDailyForbiddenTimeMs() {
        return prefs.getLong(KEY_DAILY_FORBIDDEN_TIME_MS, 0);
    }

    public void setDailyForbiddenTimeMs(long timeMs) {
        prefs.edit().putLong(KEY_DAILY_FORBIDDEN_TIME_MS, timeMs).apply();
    }

    public long getDailySessionTimeSumMs() {
        return prefs.getLong(KEY_DAILY_SESSION_TIME_SUM_MS, 0);
    }

    public void setDailySessionTimeSumMs(long timeMs) {
        prefs.edit().putLong(KEY_DAILY_SESSION_TIME_SUM_MS, timeMs).apply();
    }

    public float getCompulsionIndexC() {
        return prefs.getFloat(KEY_COMPULSION_INDEX_C, 0.0f);
    }

    public void setCompulsionIndexC(float c) {
        prefs.edit().putFloat(KEY_COMPULSION_INDEX_C, c).apply();
    }

    public float getCAtLastForbidden() {
        return prefs.getFloat(KEY_C_AT_LAST_FORBIDDEN, 0.0f);
    }

    public void setCAtLastForbidden(float c) {
        prefs.edit().putFloat(KEY_C_AT_LAST_FORBIDDEN, c).apply();
    }

    public long getLastForbiddenTimestamp() {
        return prefs.getLong(KEY_LAST_FORBIDDEN_TIMESTAMP, 0);
    }

    public void setLastForbiddenTimestamp(long timestamp) {
        prefs.edit().putLong(KEY_LAST_FORBIDDEN_TIMESTAMP, timestamp).apply();
    }

    public float getDecayStep() {
        return prefs.getFloat(KEY_DECAY_STEP, 1.0f);
    }

    public void setDecayStep(float step) {
        prefs.edit().putFloat(KEY_DECAY_STEP, step).apply();
    }

    public float getGraceMultiplier() {
        return prefs.getFloat(KEY_GRACE_MULTIPLIER, 1.0f);
    }

    public void setGraceMultiplier(float multiplier) {
        prefs.edit().putFloat(KEY_GRACE_MULTIPLIER, multiplier).apply();
    }

    public long getLastDecayTimestamp() {
        return prefs.getLong(KEY_LAST_DECAY_TIMESTAMP, 0);
    }

    public void setLastDecayTimestamp(long timestamp) {
        prefs.edit().putLong(KEY_LAST_DECAY_TIMESTAMP, timestamp).apply();
    }

    // Metric Increments
    public void incrementFrictionShown() {
        int val = prefs.getInt(KEY_METRIC_FRICTION_SHOWN, 0);
        prefs.edit().putInt(KEY_METRIC_FRICTION_SHOWN, val + 1).apply();
    }
    public void incrementFrictionEndured() {
        int val = prefs.getInt(KEY_METRIC_FRICTION_ENDURED, 0);
        prefs.edit().putInt(KEY_METRIC_FRICTION_ENDURED, val + 1).apply();
    }
    public void incrementFrictionAborted() {
        int val = prefs.getInt(KEY_METRIC_FRICTION_ABORTED, 0);
        prefs.edit().putInt(KEY_METRIC_FRICTION_ABORTED, val + 1).apply();
    }
    public void recordRetryLatency(long ms) {
        long sum = prefs.getLong(KEY_METRIC_RETRY_LATENCY_SUM, 0);
        int count = prefs.getInt(KEY_METRIC_RETRY_COUNT, 0);
        prefs.edit().putLong(KEY_METRIC_RETRY_LATENCY_SUM, sum + ms)
                   .putInt(KEY_METRIC_RETRY_COUNT, count + 1)
                   .apply();
    }

    /**
     * HIGH-04: Writes all daily-reset fields atomically in a single synchronous commit.
     * Prevents partial-reset state on process death during forceResetBudget().
     */
    public void commitResetBatch(long remaining, int sessionCount, String date,
                                 long dailyForbiddenMs, long dailySessionSumMs) {
        prefs.edit()
                .putLong(KEY_REMAINING_POTENTIAL_UNITS, remaining)
                .putInt(KEY_DAILY_SESSION_COUNT, sessionCount)
                .putString(KEY_LAST_BUDGET_RESET_DATE, date)
                .putLong(KEY_DAILY_FORBIDDEN_TIME_MS, dailyForbiddenMs)
                .putLong(KEY_DAILY_SESSION_TIME_SUM_MS, dailySessionSumMs)
                .commit();
    }

    /**
     * Writes all budget-depletion fields atomically in a single synchronous commit.
     * Prevents the data-inconsistency window that occurs when the process is killed
     * between individual apply() calls and the single commit() on remainingUnits.
     */
    public void commitDepletionBatch(long dailyForbiddenMs, long dailySessionSumMs,
                                     float compulsionC, float cAtForbidden,
                                     long remainingUnits, long forbiddenTimestamp) {
        prefs.edit()
                .putLong(KEY_DAILY_FORBIDDEN_TIME_MS, dailyForbiddenMs)
                .putLong(KEY_DAILY_SESSION_TIME_SUM_MS, dailySessionSumMs)
                .putFloat(KEY_COMPULSION_INDEX_C, compulsionC)
                .putFloat(KEY_C_AT_LAST_FORBIDDEN, cAtForbidden)
                .putLong(KEY_REMAINING_POTENTIAL_UNITS, remainingUnits)
                .putLong(KEY_LAST_FORBIDDEN_TIMESTAMP, forbiddenTimestamp)
                .commit();
    }
}
