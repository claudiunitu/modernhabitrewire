package com.example.modernhabitrewire;

import android.content.Context;
import android.content.SharedPreferences;

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
    
    private static final String KEY_DAILY_BUDGET_MINS = "daily_budget_mins";
    private static final String KEY_REMAINING_BUDGET_MS = "remaining_budget_ms";
    private static final String KEY_LAST_BUDGET_RESET_DATE = "last_budget_reset_date";
    private static final String KEY_TEMP_ALLOW_APP_LAUNCH = "temp_allow_app_launch";
    private static final String KEY_LAST_INTERCEPTED_APP = "last_intercepted_app";
    private static final String KEY_DAILY_SESSION_COUNT = "daily_session_count";
    private static final String KEY_BASE_WAIT_TIME_SECONDS = "base_wait_time_seconds";
    private static final String KEY_COST_INCREMENT_FACTOR = "cost_increment_factor";
    private static final String KEY_LAUNCH_FRICTION_ENABLED = "launch_friction_enabled";

    private static final String DEACTIVATION_KEY = "deactivation_key";
    private static final String BYPASS_SWITCH_VALUE = "bypass_switch_value";
    private static final String FORBID_SETTINGS_SWITCH_VALUE = "forbid_settings_switch_value";

    private SharedPreferences prefs;

    public AppPreferencesManagerSingleton(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static AppPreferencesManagerSingleton getInstance(Context context) {
        if (_instance == null) {
            _instance = new AppPreferencesManagerSingleton(context);
        }
        return AppPreferencesManagerSingleton._instance;
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

    public void setDeactivationKey(String key){
        prefs.edit().putString(DEACTIVATION_KEY, key).apply();
    }

    public String getDeactivationKey(){
        return prefs.getString(DEACTIVATION_KEY, "");
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

    public int getDailyBudgetMinutes() {
        return prefs.getInt(KEY_DAILY_BUDGET_MINS, 30);
    }

    public void setDailyBudgetMinutes(int minutes) {
        prefs.edit().putInt(KEY_DAILY_BUDGET_MINS, minutes).apply();
    }

    public long getRemainingDopamineBudgetMs() {
        return prefs.getLong(KEY_REMAINING_BUDGET_MS, 0);
    }

    public void setRemainingDopamineBudgetMs(long ms) {
        prefs.edit().putLong(KEY_REMAINING_BUDGET_MS, ms).apply();
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
        prefs.edit().putString(KEY_LAST_INTERCEPTED_APP, packageName).apply();
    }

    public int getDailySessionCount() {
        return prefs.getInt(KEY_DAILY_SESSION_COUNT, 0);
    }

    public void setDailySessionCount(int count) {
        prefs.edit().putInt(KEY_DAILY_SESSION_COUNT, count).apply();
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
}
