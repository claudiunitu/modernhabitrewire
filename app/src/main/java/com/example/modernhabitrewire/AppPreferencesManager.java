package com.example.modernhabitrewire;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class AppPreferencesManager {
    private static final String PREF_NAME = "global_preferences";
    private static final String KEY_FORBIDDEN_URL_LIST = "forbidden_url_list";
    private static final String KEY_FORBIDDEN_APP_LIST = "forbidden_app_list";
    private static final String KEY_IS_BLOCKER_ACTIVE = "is_blocker_active";

    private static final String DEACTIVATION_KEY = "deactivation_key";

    private static final String BYPASS_SWITCH_VALUE = "bypass_switch_value";
    private static final String FORBID_SETTINGS_SWITCH_VALUE = "forbid_settings_switch_value";

    private final SharedPreferences prefs;
    private List<String> cachedUrls = null;
    private List<String> cachedApps = null;


    public AppPreferencesManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setForbidSettingsSwitchValue(Boolean flag){
        prefs.edit().putBoolean(FORBID_SETTINGS_SWITCH_VALUE, flag).apply();
    }

    public Boolean getForbidSettingsSwitchValue(){
        return prefs.getBoolean(FORBID_SETTINGS_SWITCH_VALUE, false);
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
    public Boolean getIsBlockerActive() {
        return prefs.getBoolean(KEY_IS_BLOCKER_ACTIVE, false);
    }

    public void setIsBlockerActive() {
        prefs.edit().putBoolean(KEY_IS_BLOCKER_ACTIVE, true).apply();
    }
    public void setIsBlockerInactive() {
        prefs.edit().putBoolean(KEY_IS_BLOCKER_ACTIVE, false).apply();
    }

    public List<String> getForbiddenUrls() {
        if (cachedUrls != null) {
            return cachedUrls;
        }
        String raw = prefs.getString(KEY_FORBIDDEN_URL_LIST, null);
        if (raw == null || raw.isEmpty()) {
            return getDefaultUrls();
        }
        return new ArrayList<>(List.of(raw.split(",")));
    }

    public void setForbiddenApps(List<String> apps) {
        cachedApps = new ArrayList<>(apps); // update cache
        String joined = String.join(",", cachedApps);
        prefs.edit().putString(KEY_FORBIDDEN_APP_LIST, joined).apply();
    }

    public List<String> getForbiddenApps() {
        if (cachedApps != null) {
            return cachedApps;
        }
        String raw = prefs.getString(KEY_FORBIDDEN_APP_LIST, null);
        if (raw == null || raw.isEmpty()) {
            return getDefaultApps();
        }
        return new ArrayList<>(List.of(raw.split(",")));
    }

    public void setForbiddenUrls(List<String> urls) {
        cachedUrls = new ArrayList<>(urls); // update cache
        String joined = String.join(",", cachedUrls);
        prefs.edit().putString(KEY_FORBIDDEN_URL_LIST, joined).apply();
    }

    public void addUrl(String url) {
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

    private List<String> getDefaultUrls() {
        return new ArrayList<>(List.of(
                "default.example.com"
        ));
    }
    private List<String> getDefaultApps() {
        return new ArrayList<>(List.of(
                "com.google.android.youtube"
        ));
    }

}
