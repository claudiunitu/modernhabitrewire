package com.example.voward;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AppPreferencesManagerSingleton {

    private static AppPreferencesManagerSingleton _instance = null;
    private static final String PREF_NAME = "global_preferences";
    private static final String PORTABLE_PREF_NAME = "portable_preferences";
    private static final String KEY_PORTABLE_MIGRATION_COMPLETE = "portable_migration_complete_v1";
    public static final int PORTABLE_SCHEMA_VERSION = 6;
    private static final String KEY_RESTRICTED_URL_LIST = "restricted_url_list";
    private static final String KEY_RESTRICTED_APP_LIST = "restricted_app_list";
    private static final String KEY_STRICT_URL_LIST = "strict_restricted_url_list";
    private static final String KEY_STRICT_APP_LIST = "strict_restricted_app_list";
    private static final String KEY_IS_BLOCKER_ACTIVE = "is_blocker_active";
    
    private static final String KEY_DAILY_ALLOWANCE_SECONDS = "daily_allowance_seconds";
    private static final String KEY_REMAINING_BUDGET_SECONDS = "remaining_budget_seconds";
    private static final String KEY_LAST_BUDGET_RESET_DATE = "last_budget_reset_date";
    private static final String KEY_LAST_BUDGET_RESET_EPOCH_DAY = "last_budget_reset_epoch_day";
    private static final String KEY_TEMP_ALLOW_APP_LAUNCH = "temp_allow_app_launch";
    private static final String KEY_LAST_INTERCEPTED_APP = "last_intercepted_app";
    private static final String KEY_LAST_INTERCEPTED_URL = "last_intercepted_url";
    private static final String KEY_DAILY_SESSION_COUNT = "daily_session_count";
    private static final String KEY_BASE_WAIT_TIME_SECONDS = "base_wait_time_seconds";
    private static final String KEY_LAUNCH_FRICTION_ENABLED = "launch_friction_enabled";
    private static final String KEY_MODEL_VERSION = "attention_model_version";
    private static final String KEY_REENTRY_GROWTH = "reentry_growth";
    private static final String KEY_DEFAULT_SESSION_SECONDS = "default_session_seconds";
    private static final String KEY_CARRYOVER_CAP_DAYS = "carryover_cap_days";
    private static final String KEY_PENDING_SESSION_SECONDS = "pending_session_seconds";
    private static final String KEY_PENDING_QUOTED_SESSION_SECONDS = "pending_quoted_session_seconds";
    private static final String KEY_LAST_INTERCEPTION_KIND = "last_interception_kind";
    private static final String KEY_PERMISSION_DISCLOSURE_ACCEPTED = "permission_disclosure_accepted";
    private static final String KEY_SETUP_SEEN = "guided_setup_seen_v1";
    private static final String KEY_FUNCTIONAL_GOAL = "functional_goal";
    private static final String KEY_REPLACEMENT_WALK = "replacement_walk";
    private static final String KEY_REPLACEMENT_WATER = "replacement_water";
    private static final String KEY_REPLACEMENT_TASK = "replacement_task";

    private static final String KEY_DEACTIVATION_HASH = "deactivation_hash";
    private static final String KEY_UNINSTALL_GUARD_ENABLED = "uninstall_guard_enabled";
    private static final String KEY_DEACTIVATION_COOLDOWN_HOURS = "deactivation_cooldown_hours";
    private static final String KEY_DEACTIVATION_COOLDOWN_MINUTES = "deactivation_cooldown_minutes";
    private static final String KEY_DEACTIVATION_WINDOW_HOURS = "deactivation_window_hours";
    private static final String KEY_PENDING_DEACTIVATION = "pending_deactivation_v1";
    private static final String KEY_DEACTIVATION_TERMINAL_STATE = "deactivation_terminal_state_v1";
    public static final int DEFAULT_DEACTIVATION_COOLDOWN_MINUTES = 24 * 60;
    public static final int DEFAULT_DEACTIVATION_WINDOW_HOURS = 1;
    private static final int[] ALLOWED_COOLDOWN_MINUTES = {0, 1, 360, 720, 1440, 2880, 4320};
    private static final int[] ALLOWED_WINDOW_HOURS = {1, 2, 3, 6, 12, 24};

    private static final String KEY_DAILY_RESTRICTED_TIME_MS = "daily_restricted_time_ms";

    // Decision-gate metrics
    private static final String KEY_METRIC_FRICTION_SHOWN = "metric_friction_shown";
    private static final String KEY_METRIC_FRICTION_ENDURED = "metric_friction_endured";
    private static final String KEY_METRIC_FRICTION_ABORTED = "metric_friction_aborted";
    private static final String KEY_METRIC_RETRY_LATENCY_SUM = "metric_retry_latency_sum";
    private static final String KEY_METRIC_RETRY_COUNT = "metric_retry_count";
    private static final String KEY_METRIC_SESSIONS_ENDED_EARLY = "metric_sessions_ended_early";
    private static final String KEY_METRIC_SESSION_LIMIT_REACHED = "metric_session_limit_reached";
    private static final String KEY_DAILY_USAGE_HISTORY = "daily_usage_history_v1";
    private static final String KEY_DAILY_SESSION_HOURS = "daily_session_hours_v1";
    private static final String KEY_DAILY_ALTERNATIVE_CHOICES = "daily_alternative_choices_v1";
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int PBKDF2_BITS = 256;

    private final SharedPreferences prefs;
    private final SharedPreferences portablePrefs;
    private final Context appContext;
    private volatile List<String> restrictedUrlsCache;
    private volatile List<String> restrictedAppsCache;
    private volatile List<String> strictUrlsCache;
    private volatile List<String> strictAppsCache;
    private volatile boolean restrictedUrlsStorageCorrupt;
    private volatile boolean restrictedAppsStorageCorrupt;
    private volatile boolean strictUrlsStorageCorrupt;
    private volatile boolean strictAppsStorageCorrupt;

    private AppPreferencesManagerSingleton(Context context) {
        // Use application context to avoid leaking Activity/Service contexts
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        portablePrefs = appContext.getSharedPreferences(PORTABLE_PREF_NAME, Context.MODE_PRIVATE);
        migratePortablePreferencesIfNeeded();
        migrateAttentionModelIfNeeded();
    }

    /** Preserve settings from installs that predate the portable/device-local split. */
    private void migratePortablePreferencesIfNeeded() {
        if (portablePrefs.getBoolean(KEY_PORTABLE_MIGRATION_COMPLETE, false)) return;
        SharedPreferences.Editor out = portablePrefs.edit();
        if (prefs.contains("forbidden_url_list")) {
            out.putString(KEY_RESTRICTED_URL_LIST, prefs.getString("forbidden_url_list", ""));
        }
        if (prefs.contains("extractive_app_list")) {
            out.putString(KEY_RESTRICTED_APP_LIST,
                    prefs.getString("extractive_app_list", "com.google.android.youtube"));
        }
        if (prefs.contains("daily_allowance_units")) {
            out.putInt(KEY_DAILY_ALLOWANCE_SECONDS,
                    prefs.getInt("daily_allowance_units", 1800));
        }
        copyIntIfPresent(out, KEY_BASE_WAIT_TIME_SECONDS);
        copyBooleanIfPresent(out, KEY_LAUNCH_FRICTION_ENABLED);
        copyFloatIfPresent(out, KEY_REENTRY_GROWTH);
        copyIntIfPresent(out, KEY_DEFAULT_SESSION_SECONDS);
        copyFloatIfPresent(out, KEY_CARRYOVER_CAP_DAYS);
        if (prefs.contains("forbid_settings_switch_value")) {
            out.putBoolean(KEY_UNINSTALL_GUARD_ENABLED,
                    prefs.getBoolean("forbid_settings_switch_value", false));
        }
        out.putBoolean(KEY_PORTABLE_MIGRATION_COMPLETE, true).apply();
    }

    /** Move installs to descriptive storage names, remove retired state, and erase old debt. */
    private synchronized void migrateAttentionModelIfNeeded() {
        if (prefs.getInt(KEY_MODEL_VERSION, 0) >= 6) return;
        if (!portablePrefs.contains(KEY_DAILY_ALLOWANCE_SECONDS)) {
            portablePrefs.edit().putInt(KEY_DAILY_ALLOWANCE_SECONDS,
                    portablePrefs.getInt("daily_allowance_units", 1800)).apply();
        }
        int allowance = getDailyAllowanceSeconds();
        long storedBalance = prefs.contains(KEY_REMAINING_BUDGET_SECONDS)
                ? prefs.getLong(KEY_REMAINING_BUDGET_SECONDS, 0)
                : prefs.getLong("remaining_potential_units", 0);
        long bounded = BudgetMath.addDailyAllowancesBounded(
                storedBalance, allowance, 0, getCarryoverCapDays());
        prefs.edit()
                .putInt(KEY_MODEL_VERSION, 6)
                .putLong(KEY_REMAINING_BUDGET_SECONDS, bounded)
                .remove("safety_unlock_available_at")
                .remove("remaining_potential_units")
                .remove("daily_forbidden_time_ms")
                .remove("daily_session_time_sum_ms")
                .remove("compulsion_index_c")
                .remove("c_at_last_forbidden")
                .remove("last_forbidden_timestamp")
                .remove("last_decay_timestamp")
                .remove("forbidden_url_list")
                .remove("extractive_app_list")
                .remove("daily_allowance_units")
                .remove("bypass_switch_value")
                .remove("forbid_settings_switch_value")
                .apply();
        SharedPreferences.Editor portableEdit = portablePrefs.edit();
        if (!portablePrefs.contains(KEY_RESTRICTED_URL_LIST)) {
            portableEdit.putString(KEY_RESTRICTED_URL_LIST,
                    portablePrefs.getString("forbidden_url_list", ""));
        }
        if (!portablePrefs.contains(KEY_RESTRICTED_APP_LIST)) {
            portableEdit.putString(KEY_RESTRICTED_APP_LIST, portablePrefs.getString(
                    "extractive_app_list", "com.google.android.youtube"));
        }
        portableEdit.remove("forbidden_url_list")
                .remove("extractive_app_list")
                .remove("daily_allowance_units")
                .remove("charging_bypass_enabled")
                .remove("cost_increment_factor")
                .remove("decay_step")
                .remove("grace_multiplier")
                .apply();
        if (!portablePrefs.contains(KEY_UNINSTALL_GUARD_ENABLED)) {
            portablePrefs.edit().putBoolean(KEY_UNINSTALL_GUARD_ENABLED,
                    portablePrefs.getBoolean("forbid_settings_switch_value", false))
                    .remove("forbid_settings_switch_value").apply();
        }
        if (!prefs.contains(KEY_DEACTIVATION_HASH) && prefs.contains("deactivation_key")) {
            prefs.edit().putString(KEY_DEACTIVATION_HASH,
                    prefs.getString("deactivation_key", ""))
                    .remove("deactivation_key").apply();
        }
        portablePrefs.edit()
                .remove("bypass_switch_value")
                .remove("forbid_settings_switch_value")
                .apply();
        prefs.edit().remove("deactivation_key").apply();
    }

    private void copyIntIfPresent(SharedPreferences.Editor out, String key) {
        if (prefs.contains(key)) out.putInt(key, prefs.getInt(key, 0));
    }
    private void copyFloatIfPresent(SharedPreferences.Editor out, String key) {
        if (prefs.contains(key)) out.putFloat(key, prefs.getFloat(key, 0));
    }
    private void copyBooleanIfPresent(SharedPreferences.Editor out, String key) {
        if (prefs.contains(key)) out.putBoolean(key, prefs.getBoolean(key, false));
    }

    public static synchronized AppPreferencesManagerSingleton getInstance(Context context) {
        if (_instance == null) {
            _instance = new AppPreferencesManagerSingleton(context);
        }
        return _instance;
    }

    public void setUninstallGuardEnabled(boolean enabled) {
        portablePrefs.edit().putBoolean(KEY_UNINSTALL_GUARD_ENABLED, enabled).apply();
    }

    public boolean isUninstallGuardEnabled() {
        return portablePrefs.getBoolean(KEY_UNINSTALL_GUARD_ENABLED, false);
    }

    public void setIsBlockerActive(Boolean flag) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_IS_BLOCKER_ACTIVE, flag);
        // Activation and successful deactivation both atomically discard stale local requests.
        editor.remove(KEY_PENDING_DEACTIVATION)
                .remove(KEY_DEACTIVATION_TERMINAL_STATE).commit();
    }

    public int getDeactivationCooldownMinutes() {
        if (portablePrefs.contains(KEY_DEACTIVATION_COOLDOWN_MINUTES)) {
            return portablePrefs.getInt(KEY_DEACTIVATION_COOLDOWN_MINUTES,
                    DEFAULT_DEACTIVATION_COOLDOWN_MINUTES);
        }
        // Schema 5 and existing installs stored this policy in whole hours.
        return portablePrefs.getInt(KEY_DEACTIVATION_COOLDOWN_HOURS, 24) * 60;
    }

    public void setDeactivationCooldownMinutes(int minutes) {
        requireAllowed(minutes, ALLOWED_COOLDOWN_MINUTES, "cooldown");
        portablePrefs.edit().putInt(KEY_DEACTIVATION_COOLDOWN_MINUTES, minutes)
                .remove(KEY_DEACTIVATION_COOLDOWN_HOURS).apply();
    }

    public int getDeactivationWindowHours() {
        return portablePrefs.getInt(KEY_DEACTIVATION_WINDOW_HOURS,
                DEFAULT_DEACTIVATION_WINDOW_HOURS);
    }

    public void setDeactivationWindowHours(int hours) {
        requireAllowed(hours, ALLOWED_WINDOW_HOURS, "window");
        portablePrefs.edit().putInt(KEY_DEACTIVATION_WINDOW_HOURS, hours).apply();
    }

    public synchronized void savePendingDeactivation(DeactivationPolicyEngine.Request request) {
        if (request == null) { clearPendingDeactivation(); return; }
        try {
            JSONObject value = new JSONObject()
                    .put("id", request.id).put("wallTimeMs", request.wallTimeMs)
                    .put("elapsedRealtimeMs", request.elapsedRealtimeMs)
                    .put("bootCount", request.bootCount).put("cooldownMs", request.cooldownMs)
                    .put("windowMs", request.windowMs);
            prefs.edit().putString(KEY_PENDING_DEACTIVATION, value.toString())
                    .remove(KEY_DEACTIVATION_TERMINAL_STATE).commit();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized DeactivationPolicyEngine.Request getPendingDeactivation() {
        String raw = prefs.getString(KEY_PENDING_DEACTIVATION, null);
        if (raw == null) return null;
        try {
            JSONObject value = new JSONObject(raw);
            return new DeactivationPolicyEngine.Request(value.getString("id"),
                    value.getLong("wallTimeMs"), value.getLong("elapsedRealtimeMs"),
                    value.getInt("bootCount"), value.getLong("cooldownMs"),
                    value.getLong("windowMs"));
        } catch (JSONException | RuntimeException corrupted) {
            clearPendingDeactivation();
            return null;
        }
    }

    public synchronized void clearPendingDeactivation() {
        prefs.edit().remove(KEY_PENDING_DEACTIVATION)
                .remove(KEY_DEACTIVATION_TERMINAL_STATE).commit();
    }

    public synchronized void finishPendingDeactivation(DeactivationPolicyEngine.State state) {
        if (state != DeactivationPolicyEngine.State.EXPIRED
                && state != DeactivationPolicyEngine.State.INVALIDATED) {
            throw new IllegalArgumentException("Not a terminal request state: " + state);
        }
        prefs.edit().remove(KEY_PENDING_DEACTIVATION)
                .putString(KEY_DEACTIVATION_TERMINAL_STATE, state.name()).commit();
    }

    public DeactivationPolicyEngine.State getDeactivationTerminalState() {
        String value = prefs.getString(KEY_DEACTIVATION_TERMINAL_STATE, null);
        if (value == null) return null;
        try {
            return DeactivationPolicyEngine.State.valueOf(value);
        } catch (IllegalArgumentException corrupted) {
            prefs.edit().remove(KEY_DEACTIVATION_TERMINAL_STATE).apply();
            return null;
        }
    }

    public Boolean getIsBlockerActive() {
        return prefs.getBoolean(KEY_IS_BLOCKER_ACTIVE, false);
    }

    private static String sha256Hex(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present on all Android versions
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String createPasswordHash(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(input.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_BITS);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect deactivation key", e);
        }
    }

    /** Store a salted, deliberately slow hash so plaintext is never persisted. */
    public void setDeactivationKey(String key) {
        prefs.edit().putString(KEY_DEACTIVATION_HASH, createPasswordHash(key)).apply();
    }

    /** Returns true if the stored hash is empty (no key has been set). */
    public String getDeactivationKey() {
        return prefs.getString(KEY_DEACTIVATION_HASH, "");
    }

    /** Compare a raw input against the stored hash without exposing the key. */
    public boolean verifyDeactivationKey(String input) {
        String stored = getDeactivationKey();
        if (stored.isEmpty() || input == null) return false;
        try {
            if (stored.startsWith("pbkdf2$")) {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) return false;
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                PBEKeySpec spec = new PBEKeySpec(input.toCharArray(), salt,
                        Integer.parseInt(parts[1]), expected.length * 8);
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                spec.clearPassword();
                return MessageDigest.isEqual(expected, actual);
            }
            boolean valid = MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.US_ASCII),
                    sha256Hex(input).getBytes(StandardCharsets.US_ASCII));
            if (valid) setDeactivationKey(input);
            return valid;
        } catch (Exception ignored) {
            return false;
        }
    }

    public List<String> getRestrictedUrls() {
        List<String> cached = restrictedUrlsCache;
        if (cached == null) {
            synchronized (this) {
                if (restrictedUrlsCache == null) {
                    DecodedStringList decoded = requireValidUrlRules(
                            readStringList(KEY_RESTRICTED_URL_LIST, ""));
                    restrictedUrlsCache = immutableList(decoded.values);
                    restrictedUrlsStorageCorrupt = decoded.corrupt;
                }
                cached = restrictedUrlsCache;
            }
        }
        return new ArrayList<>(cached);
    }

    public List<String> getRestrictedUrlsSnapshot() {
        getRestrictedUrls();
        if (getIsBlockerActive() && restrictedUrlsStorageCorrupt) {
            return Collections.singletonList(UrlPatternMatcher.FAIL_CLOSED_PATTERN);
        }
        return restrictedUrlsCache;
    }

    public synchronized void setRestrictedUrls(List<String> urls) {
        if (getIsBlockerActive() && restrictedUrlsStorageCorrupt) return;
        List<String> updated = sanitizeList(urls);
        if (getIsBlockerActive()) {
            for (String existing : getRestrictedUrls()) {
                if (!containsIgnoreCase(updated, existing)) updated.add(existing);
            }
        }
        restrictedUrlsCache = immutableList(updated);
        restrictedUrlsStorageCorrupt = false;
        List<String> strict = getStrictRestrictedUrls();
        strict.removeIf(value -> !containsIgnoreCase(restrictedUrlsCache, value));
        strictUrlsCache = immutableList(strict);
        portablePrefs.edit()
                .putString(KEY_RESTRICTED_URL_LIST,
                        new JSONArray(restrictedUrlsCache).toString())
                .putString(KEY_STRICT_URL_LIST,
                        new JSONArray(strictUrlsCache).toString())
                .apply();
    }

    /** Hot-path matcher that does not allocate a defensive copy for each accessibility event. */
    public String findRestrictedUrlPattern(String url) {
        for (String pattern : getRestrictedUrlsSnapshot()) {
            if (UrlPatternMatcher.matches(url, pattern)) return pattern;
        }
        return null;
    }

    public void addRestrictedUrl(String url) {
        List<String> urls = getRestrictedUrls();
        String clean = sanitizeItem(url);
        if (!clean.isEmpty() && !containsIgnoreCase(urls, clean)) {
            urls.add(clean);
            setRestrictedUrls(urls);
        }
    }

    public synchronized void addRestrictedUrl(String url, boolean strict) {
        if (getIsBlockerActive() && restrictedUrlsStorageCorrupt) return;
        String clean = sanitizeItem(url);
        if (clean.isEmpty()) return;
        List<String> urls = getRestrictedUrls();
        if (!containsIgnoreCase(urls, clean)) urls.add(clean);
        List<String> strictUrls = getStrictRestrictedUrls();
        if (strict && !containsIgnoreCase(strictUrls, clean)) strictUrls.add(clean);
        restrictedUrlsCache = immutableList(sanitizeList(urls));
        strictUrlsCache = immutableList(sanitizeList(strictUrls));
        portablePrefs.edit()
                .putString(KEY_RESTRICTED_URL_LIST, new JSONArray(restrictedUrlsCache).toString())
                .putString(KEY_STRICT_URL_LIST, new JSONArray(strictUrlsCache).toString())
                .apply();
    }

    public void removeUrl(String url) {
        if (getIsBlockerActive()) return;
        List<String> urls = getRestrictedUrls();
        urls.removeIf(value -> value.equalsIgnoreCase(url));
        setRestrictedUrls(urls);
    }

    public List<String> getStrictRestrictedUrls() {
        List<String> cached = strictUrlsCache;
        if (cached == null) {
            synchronized (this) {
                if (strictUrlsCache == null) {
                    DecodedStringList decoded = requireValidUrlRules(
                            readStringList(KEY_STRICT_URL_LIST, ""));
                    List<String> strict = decoded.values;
                    strictUrlsStorageCorrupt = decoded.corrupt;
                    strict.removeIf(value -> !containsIgnoreCase(getRestrictedUrls(), value));
                    strictUrlsCache = immutableList(strict);
                }
                cached = strictUrlsCache;
            }
        }
        return new ArrayList<>(cached);
    }

    public List<String> getStrictRestrictedUrlsSnapshot() {
        getRestrictedUrls();
        getStrictRestrictedUrls();
        if (getIsBlockerActive()) {
            if (restrictedUrlsStorageCorrupt) {
                return Collections.singletonList(UrlPatternMatcher.FAIL_CLOSED_PATTERN);
            }
            if (strictUrlsStorageCorrupt) return restrictedUrlsCache;
        }
        return strictUrlsCache;
    }

    public boolean isStrictRestrictedUrlPattern(String pattern) {
        return containsIgnoreCase(getStrictRestrictedUrlsSnapshot(), pattern);
    }

    public synchronized void setRestrictedUrlStrict(String url, boolean strict) {
        if (getIsBlockerActive() && strictUrlsStorageCorrupt) return;
        if (getIsBlockerActive() && !strict) return;
        String clean = sanitizeItem(url);
        if (!containsIgnoreCase(getRestrictedUrls(), clean)) return;
        List<String> strictUrls = getStrictRestrictedUrls();
        strictUrls.removeIf(value -> value.equalsIgnoreCase(clean));
        if (strict) strictUrls.add(clean);
        strictUrlsCache = immutableList(strictUrls);
        strictUrlsStorageCorrupt = false;
        portablePrefs.edit().putString(KEY_STRICT_URL_LIST,
                new JSONArray(strictUrlsCache).toString()).apply();
    }

    public List<String> getRestrictedAppPackages() {
        List<String> cached = restrictedAppsCache;
        if (cached == null) {
            synchronized (this) {
                if (restrictedAppsCache == null) {
                    DecodedStringList decoded = requireValidAppRules(readStringList(
                            KEY_RESTRICTED_APP_LIST, "com.google.android.youtube"));
                    restrictedAppsCache = immutableList(decoded.values);
                    restrictedAppsStorageCorrupt = decoded.corrupt;
                }
                cached = restrictedAppsCache;
            }
        }
        return new ArrayList<>(cached);
    }

    public synchronized void setRestrictedApps(List<String> apps) {
        if (getIsBlockerActive() && restrictedAppsStorageCorrupt) return;
        List<String> updated = sanitizeList(apps);
        if (getIsBlockerActive()) {
            for (String existing : getRestrictedAppPackages()) {
                if (!updated.contains(existing)) updated.add(existing);
            }
        }
        restrictedAppsCache = immutableList(updated);
        restrictedAppsStorageCorrupt = false;
        List<String> strict = getStrictRestrictedAppPackages();
        strict.removeIf(value -> !restrictedAppsCache.contains(value));
        strictAppsCache = immutableList(strict);
        portablePrefs.edit()
                .putString(KEY_RESTRICTED_APP_LIST,
                        new JSONArray(restrictedAppsCache).toString())
                .putString(KEY_STRICT_APP_LIST,
                        new JSONArray(strictAppsCache).toString())
                .apply();
    }

    /** Allocation-free membership check for the accessibility-event hot path. */
    public boolean isRestrictedApp(String packageName) {
        getRestrictedAppPackages(); // Populate the volatile cache once.
        if (getIsBlockerActive() && restrictedAppsStorageCorrupt) {
            return !SafetyPolicy.isCriticalPackage(packageName, appContext.getPackageName());
        }
        return restrictedAppsCache.contains(packageName);
    }

    public void addRestrictedAppPackage(String appPackage) {
        List<String> apps = getRestrictedAppPackages();
        String clean = sanitizeItem(appPackage);
        if (!clean.isEmpty() && !apps.contains(clean)) {
            apps.add(clean);
            setRestrictedApps(apps);
        }
    }

    public synchronized void addRestrictedAppPackage(String appPackage, boolean strict) {
        if (getIsBlockerActive() && restrictedAppsStorageCorrupt) return;
        String clean = sanitizeItem(appPackage);
        if (clean.isEmpty()) return;
        List<String> apps = getRestrictedAppPackages();
        if (!apps.contains(clean)) apps.add(clean);
        List<String> strictApps = getStrictRestrictedAppPackages();
        if (strict && !strictApps.contains(clean)) strictApps.add(clean);
        restrictedAppsCache = immutableList(sanitizeList(apps));
        strictAppsCache = immutableList(sanitizeList(strictApps));
        portablePrefs.edit()
                .putString(KEY_RESTRICTED_APP_LIST, new JSONArray(restrictedAppsCache).toString())
                .putString(KEY_STRICT_APP_LIST, new JSONArray(strictAppsCache).toString())
                .apply();
    }

    public void removeRestrictedAppPackage(String appPackage) {
        if (getIsBlockerActive()) return;
        List<String> apps = getRestrictedAppPackages();
        apps.remove(appPackage);
        setRestrictedApps(apps);
    }

    public List<String> getStrictRestrictedAppPackages() {
        List<String> cached = strictAppsCache;
        if (cached == null) {
            synchronized (this) {
                if (strictAppsCache == null) {
                    DecodedStringList decoded = requireValidAppRules(
                            readStringList(KEY_STRICT_APP_LIST, ""));
                    List<String> strict = decoded.values;
                    strictAppsStorageCorrupt = decoded.corrupt;
                    strict.removeIf(value -> !getRestrictedAppPackages().contains(value));
                    strictAppsCache = immutableList(strict);
                }
                cached = strictAppsCache;
            }
        }
        return new ArrayList<>(cached);
    }

    public boolean isStrictRestrictedApp(String packageName) {
        getRestrictedAppPackages();
        getStrictRestrictedAppPackages();
        if (getIsBlockerActive()
                && (restrictedAppsStorageCorrupt || strictAppsStorageCorrupt)) {
            return isRestrictedApp(packageName);
        }
        return strictAppsCache.contains(packageName);
    }

    public synchronized void setRestrictedAppStrict(String packageName, boolean strict) {
        if (getIsBlockerActive() && strictAppsStorageCorrupt) return;
        if (getIsBlockerActive() && !strict) return;
        String clean = sanitizeItem(packageName);
        if (!getRestrictedAppPackages().contains(clean)) return;
        List<String> strictApps = getStrictRestrictedAppPackages();
        strictApps.remove(clean);
        if (strict) strictApps.add(clean);
        strictAppsCache = immutableList(strictApps);
        strictAppsStorageCorrupt = false;
        portablePrefs.edit().putString(KEY_STRICT_APP_LIST,
                new JSONArray(strictAppsCache).toString()).apply();
    }

    public int getDailyAllowanceSeconds() {
        return clamp(portablePrefs.getInt(KEY_DAILY_ALLOWANCE_SECONDS, 1800), 0, 604800);
    }

    public void setDailyAllowanceSeconds(int seconds) {
        portablePrefs.edit().putInt(KEY_DAILY_ALLOWANCE_SECONDS,
                clamp(seconds, 0, 604800)).apply();
    }

    public long getRemainingBudgetSeconds() {
        return Math.max(0, prefs.getLong(KEY_REMAINING_BUDGET_SECONDS, 0));
    }

    public void setRemainingBudgetSeconds(long seconds) {
        prefs.edit().putLong(KEY_REMAINING_BUDGET_SECONDS, Math.max(0, seconds)).apply();
    }

    public String getLastBudgetResetDate() {
        return prefs.getString(KEY_LAST_BUDGET_RESET_DATE, "");
    }

    public void setLastBudgetResetDate(String date) {
        prefs.edit().putString(KEY_LAST_BUDGET_RESET_DATE, date).apply();
    }

    public long getLastBudgetResetEpochDay() {
        return prefs.getLong(KEY_LAST_BUDGET_RESET_EPOCH_DAY, Long.MIN_VALUE);
    }

    public void setLastBudgetResetEpochDay(long epochDay) {
        prefs.edit().putLong(KEY_LAST_BUDGET_RESET_EPOCH_DAY, epochDay).apply();
    }

    public boolean getTempAllowAppLaunch() {
        return prefs.getBoolean(KEY_TEMP_ALLOW_APP_LAUNCH, false);
    }

    public void setTempAllowAppLaunch(boolean allowed) {
        prefs.edit().putBoolean(KEY_TEMP_ALLOW_APP_LAUNCH, allowed).apply();
    }

    public String getLastInterceptedApp() {
        return prefs.getString(KEY_LAST_INTERCEPTED_APP, "");
    }

    public void setLastInterceptedApp(String packageName) {
        prefs.edit().putString(KEY_LAST_INTERCEPTED_APP, packageName == null ? "" : packageName).apply();
    }

    public String getLastInterceptedUrl() {
        return prefs.getString(KEY_LAST_INTERCEPTED_URL, "");
    }

    public void setLastInterceptedUrl(String url) {
        prefs.edit().putString(KEY_LAST_INTERCEPTED_URL, url == null ? "" : url).apply();
    }

    public String getLastInterceptionKind() {
        return prefs.getString(KEY_LAST_INTERCEPTION_KIND, "APP");
    }

    public void setLastInterceptionKind(String kind) {
        prefs.edit().putString(KEY_LAST_INTERCEPTION_KIND,
                "URL".equals(kind) ? "URL" : "APP").apply();
    }

    public int getPendingSessionSeconds() {
        return clamp(prefs.getInt(KEY_PENDING_SESSION_SECONDS, getDefaultSessionSeconds()), 60, 3600);
    }

    public void setPendingSessionSeconds(int seconds) {
        prefs.edit().putInt(KEY_PENDING_SESSION_SECONDS, clamp(seconds, 60, 3600)).apply();
    }

    public int getPendingQuotedSessionSeconds() {
        return clamp(prefs.getInt(KEY_PENDING_QUOTED_SESSION_SECONDS,
                getPendingSessionSeconds()), 1, 3600);
    }

    public void setPendingQuotedSessionSeconds(long seconds) {
        prefs.edit().putInt(KEY_PENDING_QUOTED_SESSION_SECONDS,
                clamp((int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds)), 1, 3600)).apply();
    }

    public int getDailySessionCount() {
        return prefs.getInt(KEY_DAILY_SESSION_COUNT, 0);
    }

    public void setDailySessionCount(int count) {
        prefs.edit().putInt(KEY_DAILY_SESSION_COUNT, Math.max(0, count)).apply();
    }

    public int getBaseWaitTimeSeconds() {
        return clamp(portablePrefs.getInt(KEY_BASE_WAIT_TIME_SECONDS, 30), 1, 3600);
    }

    public void setBaseWaitTimeSeconds(int seconds) {
        portablePrefs.edit().putInt(KEY_BASE_WAIT_TIME_SECONDS, clamp(seconds, 1, 3600)).apply();
    }

    public boolean getLaunchFrictionEnabled() {
        return portablePrefs.getBoolean(KEY_LAUNCH_FRICTION_ENABLED, true);
    }

    public void setLaunchFrictionEnabled(boolean enabled) {
        portablePrefs.edit().putBoolean(KEY_LAUNCH_FRICTION_ENABLED, enabled).apply();
    }

    public float getReentryGrowth() {
        return clampFinite(portablePrefs.getFloat(KEY_REENTRY_GROWTH, 0.35f), 0, 1);
    }

    public void setReentryGrowth(float growth) {
        portablePrefs.edit().putFloat(KEY_REENTRY_GROWTH,
                clampFinite(growth, 0, 1)).apply();
    }

    public int getDefaultSessionSeconds() {
        return clamp(portablePrefs.getInt(KEY_DEFAULT_SESSION_SECONDS, 600), 60, 3600);
    }

    public void setDefaultSessionSeconds(int seconds) {
        portablePrefs.edit().putInt(KEY_DEFAULT_SESSION_SECONDS,
                clamp(seconds, 60, 3600)).apply();
    }

    public float getCarryoverCapDays() {
        return clampFinite(portablePrefs.getFloat(KEY_CARRYOVER_CAP_DAYS, 1.0f), 0, 1);
    }

    public void setCarryoverCapDays(float days) {
        portablePrefs.edit().putFloat(KEY_CARRYOVER_CAP_DAYS,
                clampFinite(days, 0, 1)).apply();
    }

    public boolean getPermissionDisclosureAccepted() {
        return prefs.getBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, false);
    }

    public void setPermissionDisclosureAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_PERMISSION_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    public boolean getSetupSeen() {
        return prefs.getBoolean(KEY_SETUP_SEEN, false);
    }

    public void setSetupSeen(boolean seen) {
        prefs.edit().putBoolean(KEY_SETUP_SEEN, seen).apply();
    }

    public String getFunctionalGoal() {
        return prefs.getString(KEY_FUNCTIONAL_GOAL, "");
    }

    public void setFunctionalGoal(String goal) {
        String clean = goal == null ? "" : goal.trim();
        if (clean.length() > 200) clean = clean.substring(0, 200);
        prefs.edit().putString(KEY_FUNCTIONAL_GOAL, clean).apply();
    }

    public String getReplacementWalk() {
        return prefs.getString(KEY_REPLACEMENT_WALK, "Take a short walk");
    }

    public void setReplacementWalk(String value) {
        prefs.edit().putString(KEY_REPLACEMENT_WALK, sanitizeSuggestion(value, "Take a short walk")).apply();
    }

    public String getReplacementWater() {
        return prefs.getString(KEY_REPLACEMENT_WATER, "Drink some water");
    }

    public void setReplacementWater(String value) {
        prefs.edit().putString(KEY_REPLACEMENT_WATER, sanitizeSuggestion(value, "Drink some water")).apply();
    }

    public String getReplacementTask() {
        return prefs.getString(KEY_REPLACEMENT_TASK, "Do one small task");
    }

    public void setReplacementTask(String value) {
        prefs.edit().putString(KEY_REPLACEMENT_TASK, sanitizeSuggestion(value, "Do one small task")).apply();
    }

    private static String sanitizeSuggestion(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return fallback;
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    public long getDailyRestrictedTimeMs() {
        return prefs.getLong(KEY_DAILY_RESTRICTED_TIME_MS, 0);
    }

    /** A completed day's local-only counters, used by the Progress screen. */
    public static final class DailyUsage {
        public final String date;
        public final long restrictedTimeMs;
        public final int sessions;
        public final int endedEarly;
        public final int limitsReached;
        public final int[] sessionHours;
        public final int[] alternativeChoices;

        DailyUsage(String date, long restrictedTimeMs, int sessions,
                   int endedEarly, int limitsReached, int[] sessionHours,
                   int[] alternativeChoices) {
            this.date = date;
            this.restrictedTimeMs = Math.max(0, restrictedTimeMs);
            this.sessions = Math.max(0, sessions);
            this.endedEarly = Math.max(0, endedEarly);
            this.limitsReached = Math.max(0, limitsReached);
            this.sessionHours = sessionHours == null ? new int[24] : sessionHours.clone();
            this.alternativeChoices = alternativeChoices == null
                    ? new int[3] : alternativeChoices.clone();
        }
    }

    public List<DailyUsage> getDailyUsageHistory() {
        List<DailyUsage> result = new ArrayList<>();
        try {
            JSONArray history = new JSONArray(prefs.getString(KEY_DAILY_USAGE_HISTORY, "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);
                result.add(new DailyUsage(item.optString("date", ""),
                        item.optLong("restrictedTimeMs", 0),
                        item.optInt("sessions", 0),
                        item.optInt("endedEarly", 0),
                        item.optInt("limitsReached", 0),
                        decodeHourCounts(item.optJSONArray("sessionHours")),
                        decodeCounts(item.optJSONArray("alternativeChoices"), 3)));
            }
        } catch (JSONException ignored) {
            // Corrupt optional history must never affect enforcement.
        }
        return Collections.unmodifiableList(result);
    }

    private void archiveCurrentDayIfPresent() {
        String date = getLastBudgetResetDate();
        if (date.isEmpty()) return;
        try {
            JSONArray oldHistory = new JSONArray(prefs.getString(KEY_DAILY_USAGE_HISTORY, "[]"));
            JSONArray history = new JSONArray();
            for (int i = 0; i < oldHistory.length(); i++) {
                JSONObject item = oldHistory.getJSONObject(i);
                if (!date.equals(item.optString("date"))) history.put(item);
            }
            history.put(new JSONObject()
                    .put("date", date)
                    .put("restrictedTimeMs", getDailyRestrictedTimeMs())
                    .put("sessions", getDailySessionCount())
                    .put("endedEarly", getSessionsEndedEarlyCount())
                    .put("limitsReached", getSessionLimitReachedCount())
                    .put("sessionHours", new JSONArray(getDailySessionHourCounts()))
                    .put("alternativeChoices", new JSONArray(getDailyAlternativeChoiceCounts())));
            while (history.length() > 14) history.remove(0);
            prefs.edit().putString(KEY_DAILY_USAGE_HISTORY, history.toString()).apply();
        } catch (JSONException ignored) {
            // Progress history is best-effort and never blocks the daily reset.
        }
    }

    // Metric increments
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

    public int getFrictionAbortedCount() {
        return prefs.getInt(KEY_METRIC_FRICTION_ABORTED, 0);
    }

    public int getSessionsEndedEarlyCount() {
        return prefs.getInt(KEY_METRIC_SESSIONS_ENDED_EARLY, 0);
    }

    public int getSessionLimitReachedCount() {
        return prefs.getInt(KEY_METRIC_SESSION_LIMIT_REACHED, 0);
    }

    public void recordSessionOutcome(boolean limitReached) {
        String key = limitReached ? KEY_METRIC_SESSION_LIMIT_REACHED : KEY_METRIC_SESSIONS_ENDED_EARLY;
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    public int[] getDailySessionHourCounts() {
        try {
            return decodeHourCounts(new JSONArray(
                    prefs.getString(KEY_DAILY_SESSION_HOURS, "[]")));
        } catch (JSONException ignored) {
            return new int[24];
        }
    }

    public void incrementSessionStartHour(int hour) {
        if (hour < 0 || hour > 23) return;
        int[] counts = getDailySessionHourCounts();
        counts[hour]++;
        prefs.edit().putString(KEY_DAILY_SESSION_HOURS, encodeHourCounts(counts)).apply();
    }

    public int[] getDailyAlternativeChoiceCounts() {
        try {
            return decodeCounts(new JSONArray(
                    prefs.getString(KEY_DAILY_ALTERNATIVE_CHOICES, "[]")), 3);
        } catch (JSONException ignored) {
            return new int[3];
        }
    }

    public void incrementAlternativeChoice(int index) {
        if (index < 0 || index >= 3) return;
        int[] counts = getDailyAlternativeChoiceCounts();
        counts[index]++;
        JSONArray encoded = new JSONArray();
        for (int count : counts) encoded.put(count);
        prefs.edit().putString(KEY_DAILY_ALTERNATIVE_CHOICES,
                encoded.toString()).apply();
    }

    private static int[] decodeCounts(JSONArray array, int size) {
        int[] result = new int[size];
        if (array == null) return result;
        for (int i = 0; i < Math.min(size, array.length()); i++) {
            result[i] = Math.max(0, array.optInt(i, 0));
        }
        return result;
    }

    private static int[] decodeHourCounts(JSONArray array) {
        int[] result = new int[24];
        if (array == null) return result;
        for (int i = 0; i < Math.min(24, array.length()); i++) {
            result[i] = Math.max(0, array.optInt(i, 0));
        }
        return result;
    }

    private static String encodeHourCounts(int[] counts) {
        JSONArray array = new JSONArray();
        for (int hour = 0; hour < 24; hour++) {
            array.put(counts != null && hour < counts.length ? Math.max(0, counts[hour]) : 0);
        }
        return array.toString();
    }

    /** Archives the completed day, then applies the next day's reset state. */
    public void applyResetBatch(long remaining, int sessionCount, String date, long epochDay) {
        if (epochDay > getLastBudgetResetEpochDay()) archiveCurrentDayIfPresent();
        prefs.edit()
                .putLong(KEY_REMAINING_BUDGET_SECONDS, Math.max(0, remaining))
                .putInt(KEY_DAILY_SESSION_COUNT, sessionCount)
                .putString(KEY_LAST_BUDGET_RESET_DATE, date)
                .putLong(KEY_LAST_BUDGET_RESET_EPOCH_DAY, epochDay)
                .putLong(KEY_DAILY_RESTRICTED_TIME_MS, 0)
                .putInt(KEY_METRIC_SESSIONS_ENDED_EARLY, 0)
                .putInt(KEY_METRIC_SESSION_LIMIT_REACHED, 0)
                .putString(KEY_DAILY_SESSION_HOURS, "[]")
                .putString(KEY_DAILY_ALTERNATIVE_CHOICES, "[]")
                .apply();
    }

    /** Records only the incremental portion of an active session not saved previously. */
    public void applyUsageDelta(long durationDeltaMs, long usedSecondsDelta) {
        prefs.edit()
                .putLong(KEY_DAILY_RESTRICTED_TIME_MS,
                        getDailyRestrictedTimeMs() + Math.max(0, durationDeltaMs))
                .putLong(KEY_REMAINING_BUDGET_SECONDS,
                        BudgetMath.subtractCost(getRemainingBudgetSeconds(),
                                Math.max(0, usedSecondsDelta)))
                .apply();
    }

    /** Clears today's usage metrics without changing the remaining allowance or reset marker. */
    public void resetTodayStatistics() {
        prefs.edit()
                .putInt(KEY_DAILY_SESSION_COUNT, 0)
                .putLong(KEY_DAILY_RESTRICTED_TIME_MS, 0)
                .putInt(KEY_METRIC_FRICTION_SHOWN, 0)
                .putInt(KEY_METRIC_FRICTION_ENDURED, 0)
                .putInt(KEY_METRIC_FRICTION_ABORTED, 0)
                .putLong(KEY_METRIC_RETRY_LATENCY_SUM, 0)
                .putInt(KEY_METRIC_RETRY_COUNT, 0)
                .putInt(KEY_METRIC_SESSIONS_ENDED_EARLY, 0)
                .putInt(KEY_METRIC_SESSION_LIMIT_REACHED, 0)
                .putString(KEY_DAILY_SESSION_HOURS, "[]")
                .putString(KEY_DAILY_ALTERNATIVE_CHOICES, "[]")
                .apply();
    }

    public JSONObject exportPortableState() throws JSONException {
        return new JSONObject()
                .put("schemaVersion", PORTABLE_SCHEMA_VERSION)
                .put("restrictedUrls", new JSONArray(getRestrictedUrls()))
                .put("restrictedApps", new JSONArray(getRestrictedAppPackages()))
                .put("strictRestrictedUrls", new JSONArray(getStrictRestrictedUrls()))
                .put("strictRestrictedApps", new JSONArray(getStrictRestrictedAppPackages()))
                .put("dailyAllowanceSeconds", getDailyAllowanceSeconds())
                .put("baseWaitTimeSeconds", getBaseWaitTimeSeconds())
                .put("reentryGrowth", getReentryGrowth())
                .put("defaultSessionSeconds", getDefaultSessionSeconds())
                .put("carryoverCapDays", getCarryoverCapDays())
                .put("launchFrictionEnabled", getLaunchFrictionEnabled())
                .put("uninstallGuardEnabled", isUninstallGuardEnabled())
                .put("deactivationCooldownMinutes", getDeactivationCooldownMinutes())
                .put("deactivationWindowHours", getDeactivationWindowHours());
    }

    public synchronized void importPortableState(JSONObject data) throws JSONException {
        int version = data.getInt("schemaVersion");
        if (version < 1 || version > PORTABLE_SCHEMA_VERSION) {
            throw new JSONException("Unsupported configuration version: " + version);
        }
        List<String> urls = jsonArrayToList(data.getJSONArray(
                version >= 3 ? "restrictedUrls" : "forbiddenUrls"));
        List<String> apps = jsonArrayToList(data.getJSONArray(
                version >= 3 ? "restrictedApps" : "extractiveApps"));
        List<String> strictUrls = version >= 4
                ? jsonArrayToList(data.optJSONArray("strictRestrictedUrls") == null
                        ? new JSONArray() : data.getJSONArray("strictRestrictedUrls"))
                : new ArrayList<>();
        List<String> strictApps = version >= 4
                ? jsonArrayToList(data.optJSONArray("strictRestrictedApps") == null
                        ? new JSONArray() : data.getJSONArray("strictRestrictedApps"))
                : new ArrayList<>();
        validateImportedUrls(urls);
        validateImportedApps(apps);
        strictUrls.removeIf(value -> !containsIgnoreCase(urls, value));
        strictApps.removeIf(value -> !apps.contains(value));
        int allowance = version >= 2
                ? data.getInt("dailyAllowanceSeconds") : data.getInt("dailyAllowanceUnits");
        float growth = version >= 2
                ? (float) data.optDouble("reentryGrowth", 0.35) : 0.35f;
        int defaultSession = version >= 2
                ? data.optInt("defaultSessionSeconds", 600) : 600;
        float carryCap = version >= 2
                ? (float) data.optDouble("carryoverCapDays", 1.0) : 1.0f;
        int cooldownMinutes = version >= 6 ? data.getInt("deactivationCooldownMinutes")
                : version >= 5 ? Math.multiplyExact(data.getInt("deactivationCooldownHours"), 60)
                : DEFAULT_DEACTIVATION_COOLDOWN_MINUTES;
        int windowHours = version >= 5 ? data.getInt("deactivationWindowHours")
                : DEFAULT_DEACTIVATION_WINDOW_HOURS;
        requireAllowedJson(cooldownMinutes, ALLOWED_COOLDOWN_MINUTES,
                "deactivationCooldownMinutes");
        requireAllowedJson(windowHours, ALLOWED_WINDOW_HOURS, "deactivationWindowHours");
        portablePrefs.edit()
                .putString(KEY_RESTRICTED_URL_LIST, new JSONArray(urls).toString())
                .putString(KEY_RESTRICTED_APP_LIST, new JSONArray(apps).toString())
                .putString(KEY_STRICT_URL_LIST, new JSONArray(strictUrls).toString())
                .putString(KEY_STRICT_APP_LIST, new JSONArray(strictApps).toString())
                .putInt(KEY_DAILY_ALLOWANCE_SECONDS, clamp(allowance, 0, 604800))
                .putInt(KEY_BASE_WAIT_TIME_SECONDS, clamp(data.getInt("baseWaitTimeSeconds"), 1, 3600))
                .putFloat(KEY_REENTRY_GROWTH, clampFinite(growth, 0, 1))
                .putInt(KEY_DEFAULT_SESSION_SECONDS, clamp(defaultSession, 60, 3600))
                .putFloat(KEY_CARRYOVER_CAP_DAYS, clampFinite(carryCap, 0, 1))
                .putBoolean(KEY_LAUNCH_FRICTION_ENABLED, data.optBoolean("launchFrictionEnabled", true))
                .remove("charging_bypass_enabled")
                .putBoolean(KEY_UNINSTALL_GUARD_ENABLED, version >= 3
                        ? data.optBoolean("uninstallGuardEnabled", false)
                        : data.optBoolean("settingsLockEnabled", false))
                .putInt(KEY_DEACTIVATION_COOLDOWN_MINUTES, cooldownMinutes)
                .remove(KEY_DEACTIVATION_COOLDOWN_HOURS)
                .putInt(KEY_DEACTIVATION_WINDOW_HOURS, windowHours)
                .putBoolean(KEY_PORTABLE_MIGRATION_COMPLETE, true)
                .apply();
        restrictedUrlsCache = immutableList(urls);
        restrictedAppsCache = immutableList(apps);
        strictUrlsCache = immutableList(strictUrls);
        strictAppsCache = immutableList(strictApps);
        restrictedUrlsStorageCorrupt = false;
        restrictedAppsStorageCorrupt = false;
        strictUrlsStorageCorrupt = false;
        strictAppsStorageCorrupt = false;
    }

    private void validateImportedUrls(List<String> urls) throws JSONException {
        for (String url : urls) {
            if (!UrlPatternMatcher.isValidPattern(url)) {
                throw new JSONException("Invalid restricted URL rule: " + url);
            }
        }
    }

    private void validateImportedApps(List<String> apps) throws JSONException {
        PackageManager packageManager = appContext.getPackageManager();
        for (String appPackage : apps) {
            if (SafetyPolicy.isCriticalPackage(appPackage, appContext.getPackageName())) {
                throw new JSONException("Critical package cannot be restricted: " + appPackage);
            }
            try {
                packageManager.getApplicationInfo(appPackage, 0);
            } catch (PackageManager.NameNotFoundException missing) {
                throw new JSONException("Restricted app is not installed: " + appPackage);
            }
        }
    }

    private DecodedStringList readStringList(String key, String defaultValue) {
        try {
            return decodeStringList(portablePrefs.getString(key, defaultValue));
        } catch (ClassCastException corrupted) {
            return DecodedStringList.corrupt();
        }
    }

    private static DecodedStringList requireValidUrlRules(DecodedStringList decoded) {
        if (decoded.corrupt) return decoded;
        for (String value : decoded.values) {
            if (!UrlPatternMatcher.isValidPattern(value)) return DecodedStringList.corrupt();
        }
        return decoded;
    }

    private static DecodedStringList requireValidAppRules(DecodedStringList decoded) {
        if (decoded.corrupt) return decoded;
        for (String value : decoded.values) {
            if (!isPlausiblePackageName(value)) return DecodedStringList.corrupt();
        }
        return decoded;
    }

    private static boolean isPlausiblePackageName(String value) {
        if (value == null || value.length() > 255 || value.startsWith(".")
                || value.endsWith(".") || !value.contains(".")) return false;
        boolean previousDot = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '.') {
                if (previousDot) return false;
                previousDot = true;
            } else {
                if (!Character.isLetterOrDigit(character) && character != '_') return false;
                previousDot = false;
            }
        }
        return true;
    }

    private static DecodedStringList decodeStringList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return DecodedStringList.valid(result);
        try {
            if (raw.trim().startsWith("[")) {
                String encoded = raw.trim();
                JSONArray array = new JSONArray(encoded);
                // Android's JSON parser accepts malformed forms such as [rule. Persisted
                // lists are always written canonically, so any non-canonical array is unsafe.
                if (!array.toString().equals(encoded)) return DecodedStringList.corrupt();
                for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
            } else {
                for (String item : raw.split(",")) result.add(item); // Previous CSV format.
            }
        } catch (JSONException | RuntimeException corrupted) {
            return DecodedStringList.corrupt();
        }
        return DecodedStringList.valid(sanitizeList(result));
    }

    private static final class DecodedStringList {
        final List<String> values;
        final boolean corrupt;

        private DecodedStringList(List<String> values, boolean corrupt) {
            this.values = values;
            this.corrupt = corrupt;
        }

        static DecodedStringList valid(List<String> values) {
            return new DecodedStringList(values, false);
        }

        static DecodedStringList corrupt() {
            return new DecodedStringList(new ArrayList<>(), true);
        }
    }

    private static List<String> jsonArrayToList(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return sanitizeList(result);
    }

    private static List<String> sanitizeList(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            String clean = sanitizeItem(value);
            if (!clean.isEmpty() && !result.contains(clean)) result.add(clean);
            if (result.size() >= 500) break;
        }
        return result;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (candidate == null) return false;
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static List<String> immutableList(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String sanitizeItem(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() > 2048 ? clean.substring(0, 2048) : clean;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void requireAllowed(int value, int[] allowed, String name) {
        for (int candidate : allowed) if (candidate == value) return;
        throw new IllegalArgumentException("Unsupported deactivation " + name + ": " + value);
    }

    private static void requireAllowedJson(int value, int[] allowed, String name)
            throws JSONException {
        for (int candidate : allowed) if (candidate == value) return;
        throw new JSONException("Unsupported " + name + ": " + value);
    }

    private static float clampFinite(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
