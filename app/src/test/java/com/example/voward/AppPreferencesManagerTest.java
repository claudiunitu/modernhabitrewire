package com.example.voward;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AppPreferencesManagerTest {
    private Application application;
    private AppPreferencesManagerSingleton preferences;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        clearAllPreferences();
        installPackage("app.one");
        installPackage("legacy.app");
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);
    }

    @Test
    public void defaultsAreSafeAndConfigurationSettersClampValues() {
        assertFalse(preferences.getIsBlockerActive());
        assertEquals(1_800, preferences.getDailyAllowanceSeconds());
        assertEquals(30, preferences.getBaseWaitTimeSeconds());
        assertEquals(600, preferences.getDefaultSessionSeconds());
        assertEquals(.35f, preferences.getReentryGrowth(), 0f);
        assertEquals(1f, preferences.getCarryoverCapDays(), 0f);
        assertTrue(preferences.getLaunchFrictionEnabled());

        preferences.setDailyAllowanceSeconds(-1);
        preferences.setBaseWaitTimeSeconds(9_000);
        preferences.setDefaultSessionSeconds(1);
        preferences.setReentryGrowth(Float.NaN);
        preferences.setCarryoverCapDays(2);
        preferences.setPendingSessionSeconds(9_000);
        preferences.setPendingQuotedSessionSeconds(0);
        preferences.setDailySessionCount(-2);

        assertEquals(0, preferences.getDailyAllowanceSeconds());
        assertEquals(3_600, preferences.getBaseWaitTimeSeconds());
        assertEquals(60, preferences.getDefaultSessionSeconds());
        assertEquals(0f, preferences.getReentryGrowth(), 0f);
        assertEquals(1f, preferences.getCarryoverCapDays(), 0f);
        assertEquals(3_600, preferences.getPendingSessionSeconds());
        assertEquals(1, preferences.getPendingQuotedSessionSeconds());
        assertEquals(0, preferences.getDailySessionCount());
    }

    @Test
    public void transientAndSetupStateRoundTripsAndNormalizesInputs() {
        preferences.setIsBlockerActive(true);
        preferences.setTempAllowAppLaunch(true);
        preferences.setLastInterceptedApp(null);
        preferences.setLastInterceptedUrl(null);
        preferences.setLastInterceptionKind("bad");
        preferences.setPermissionDisclosureAccepted(true);
        preferences.setSetupSeen(true);
        preferences.setFunctionalGoal("  write a book  ");
        preferences.setRemainingBudgetSeconds(-20);

        assertTrue(preferences.getIsBlockerActive());
        assertTrue(preferences.getTempAllowAppLaunch());
        assertEquals("", preferences.getLastInterceptedApp());
        assertEquals("", preferences.getLastInterceptedUrl());
        assertEquals("APP", preferences.getLastInterceptionKind());
        assertTrue(preferences.getPermissionDisclosureAccepted());
        assertTrue(preferences.getSetupSeen());
        assertEquals("write a book", preferences.getFunctionalGoal());
        assertEquals(0, preferences.getRemainingBudgetSeconds());

        preferences.setLastInterceptionKind("URL");
        preferences.setFunctionalGoal("x".repeat(250));
        assertEquals("URL", preferences.getLastInterceptionKind());
        assertEquals(200, preferences.getFunctionalGoal().length());
    }

    @Test
    public void replacementSuggestionsNormalizeAndCompletedDayIsArchived() {
        preferences.setReplacementWalk("  Step outside  ");
        preferences.setReplacementWater("");
        preferences.setReplacementTask("x".repeat(80));
        assertEquals("Step outside", preferences.getReplacementWalk());
        assertEquals("Drink some water", preferences.getReplacementWater());
        assertEquals(60, preferences.getReplacementTask().length());

        LocalDate yesterday = LocalDate.now().minusDays(1);
        preferences.setLastBudgetResetDate(yesterday.toString());
        preferences.setLastBudgetResetEpochDay(yesterday.toEpochDay());
        preferences.setRemainingBudgetSeconds(1_000);
        preferences.setDailySessionCount(3);
        preferences.applyUsageDelta(125_000, 125);
        preferences.incrementSessionStartHour(20);
        preferences.incrementAlternativeChoice(2);
        preferences.recordSessionOutcome(false);
        preferences.recordSessionOutcome(true);

        LocalDate today = LocalDate.now();
        preferences.applyResetBatch(1_800, 0, today.toString(), today.toEpochDay());
        List<AppPreferencesManagerSingleton.DailyUsage> history =
                preferences.getDailyUsageHistory();
        assertEquals(1, history.size());
        assertEquals(yesterday.toString(), history.get(0).date);
        assertEquals(125_000, history.get(0).restrictedTimeMs);
        assertEquals(3, history.get(0).sessions);
        assertEquals(1, history.get(0).endedEarly);
        assertEquals(1, history.get(0).limitsReached);
        assertEquals(1, history.get(0).sessionHours[20]);
        assertEquals(1, history.get(0).alternativeChoices[2]);
    }

    @Test
    public void urlRulesAreSanitizedDeduplicatedDefensiveAndStrictlyTracked() {
        List<String> source = new ArrayList<>(Arrays.asList(
                " example.com ", "example.com", "", null, "other.test"));
        preferences.setRestrictedUrls(source);
        source.clear();
        assertEquals(List.of("example.com", "other.test"), preferences.getRestrictedUrls());

        List<String> returned = preferences.getRestrictedUrls();
        returned.clear();
        assertEquals(2, preferences.getRestrictedUrls().size());
        assertThrows(UnsupportedOperationException.class,
                () -> preferences.getRestrictedUrlsSnapshot().clear());

        preferences.addRestrictedUrl(" EXAMPLE.COM ", true);
        assertEquals(2, preferences.getRestrictedUrls().size());
        assertTrue(preferences.isStrictRestrictedUrlPattern("example.com"));
        assertEquals("example.com", preferences.findRestrictedUrlPattern(
                "https://sub.example.com/path"));
        preferences.setRestrictedUrlStrict("example.com", false);
        assertFalse(preferences.isStrictRestrictedUrlPattern("example.com"));
        preferences.setRestrictedUrlStrict("missing.test", true);
        assertFalse(preferences.isStrictRestrictedUrlPattern("missing.test"));

        preferences.addRestrictedUrl("other.test", true);
        preferences.removeUrl("OTHER.TEST");
        assertEquals(List.of("example.com"), preferences.getRestrictedUrls());
        assertFalse(preferences.isStrictRestrictedUrlPattern("other.test"));
    }

    @Test
    public void appRulesAreSanitizedAndStrictRulesArePrunedWithTheirParent() {
        preferences.setRestrictedApps(Arrays.asList(" app.one ", "app.one", null, "app.two"));
        assertEquals(List.of("app.one", "app.two"), preferences.getRestrictedAppPackages());
        assertTrue(preferences.isRestrictedApp("app.one"));
        assertFalse(preferences.isRestrictedApp("APP.ONE"));

        preferences.addRestrictedAppPackage("app.one", true);
        preferences.addRestrictedAppPackage("app.three", true);
        assertTrue(preferences.isStrictRestrictedApp("app.one"));
        assertTrue(preferences.isStrictRestrictedApp("app.three"));
        preferences.setRestrictedAppStrict("app.one", false);
        assertFalse(preferences.isStrictRestrictedApp("app.one"));
        preferences.removeRestrictedAppPackage("app.three");
        assertFalse(preferences.isStrictRestrictedApp("app.three"));
    }

    @Test
    public void activeProtectionPermitsOnlyAdditiveRuleChanges() {
        preferences.setRestrictedUrls(List.of("keep.example", "strict.example"));
        preferences.setRestrictedUrlStrict("strict.example", true);
        preferences.setRestrictedApps(List.of("app.keep", "app.strict"));
        preferences.setRestrictedAppStrict("app.strict", true);
        preferences.setIsBlockerActive(true);

        preferences.removeUrl("keep.example");
        preferences.setRestrictedUrls(List.of("replacement.example"));
        preferences.setRestrictedUrlStrict("strict.example", false);
        preferences.setRestrictedUrlStrict("keep.example", true);
        preferences.addRestrictedUrl("added.example", false);

        assertTrue(preferences.getRestrictedUrls().containsAll(List.of(
                "keep.example", "strict.example", "replacement.example", "added.example")));
        assertTrue(preferences.isStrictRestrictedUrlPattern("strict.example"));
        assertTrue(preferences.isStrictRestrictedUrlPattern("keep.example"));

        preferences.removeRestrictedAppPackage("app.keep");
        preferences.setRestrictedApps(List.of("app.replacement"));
        preferences.setRestrictedAppStrict("app.strict", false);
        preferences.setRestrictedAppStrict("app.keep", true);
        preferences.addRestrictedAppPackage("app.added", false);

        assertTrue(preferences.getRestrictedAppPackages().containsAll(List.of(
                "app.keep", "app.strict", "app.replacement", "app.added")));
        assertTrue(preferences.isStrictRestrictedApp("app.strict"));
        assertTrue(preferences.isStrictRestrictedApp("app.keep"));
    }

    @Test
    public void recoveryKeyUsesSaltedHashAndRejectsInvalidInput() {
        preferences.setDeactivationKey("correct horse");
        String firstHash = preferences.getDeactivationKey();
        assertTrue(firstHash.startsWith("pbkdf2$100000$"));
        assertNotEquals("correct horse", firstHash);
        assertTrue(preferences.verifyDeactivationKey("correct horse"));
        assertFalse(preferences.verifyDeactivationKey("wrong"));
        assertFalse(preferences.verifyDeactivationKey(null));

        preferences.setDeactivationKey("correct horse");
        assertNotEquals(firstHash, preferences.getDeactivationKey());
        preferences.setDeactivationKey("");
        assertFalse(preferences.verifyDeactivationKey(""));
    }

    @Test
    public void legacyShaRecoveryKeyUpgradesAfterSuccessfulVerification() throws Exception {
        String legacyHash = hex(MessageDigest.getInstance("SHA-256")
                .digest("legacy".getBytes(StandardCharsets.UTF_8)));
        global().edit().putString("deactivation_hash", legacyHash).commit();
        assertTrue(preferences.verifyDeactivationKey("legacy"));
        assertTrue(preferences.getDeactivationKey().startsWith("pbkdf2$"));
    }

    @Test
    public void usageAndResetBatchesUpdateAllPublicStatistics() {
        preferences.setRemainingBudgetSeconds(100);
        preferences.applyUsageDelta(2_500, 7);
        preferences.applyUsageDelta(-1, -1);
        assertEquals(93, preferences.getRemainingBudgetSeconds());
        assertEquals(2_500, preferences.getDailyRestrictedTimeMs());

        preferences.incrementFrictionAborted();
        preferences.recordSessionOutcome(false);
        preferences.recordSessionOutcome(true);
        assertEquals(1, preferences.getFrictionAbortedCount());
        assertEquals(1, preferences.getSessionsEndedEarlyCount());
        assertEquals(1, preferences.getSessionLimitReachedCount());

        LocalDate date = LocalDate.of(2026, 8, 11);
        preferences.applyResetBatch(80, 3, date.toString(), date.toEpochDay());
        assertEquals(80, preferences.getRemainingBudgetSeconds());
        assertEquals(3, preferences.getDailySessionCount());
        assertEquals(0, preferences.getDailyRestrictedTimeMs());
        assertEquals(0, preferences.getSessionsEndedEarlyCount());
        assertEquals(0, preferences.getSessionLimitReachedCount());

        preferences.incrementFrictionAborted();
        preferences.incrementAlternativeChoice(0);
        preferences.resetTodayStatistics();
        assertEquals(80, preferences.getRemainingBudgetSeconds());
        assertEquals(date.toEpochDay(), preferences.getLastBudgetResetEpochDay());
        assertEquals(0, preferences.getFrictionAbortedCount());
        assertEquals(0, preferences.getDailyAlternativeChoiceCounts()[0]);
    }

    @Test
    public void portableExportRoundTripsAndExcludesDeviceLocalState() throws Exception {
        preferences.setRestrictedUrls(List.of("example.com", "keyword:shorts"));
        preferences.setRestrictedUrlStrict("example.com", true);
        preferences.setRestrictedApps(List.of("app.one"));
        preferences.setRestrictedAppStrict("app.one", true);
        preferences.setDailyAllowanceSeconds(7_200);
        preferences.setBaseWaitTimeSeconds(45);
        preferences.setReentryGrowth(.8f);
        preferences.setDefaultSessionSeconds(900);
        preferences.setCarryoverCapDays(.5f);
        preferences.setLaunchFrictionEnabled(false);
        preferences.setUninstallGuardEnabled(true);
        preferences.setDeactivationCooldownMinutes(48 * 60);
        preferences.setDeactivationWindowHours(3);
        preferences.setIsBlockerActive(true);
        preferences.setRemainingBudgetSeconds(123);
        preferences.setDeactivationKey("secret");

        JSONObject exported = preferences.exportPortableState();
        assertEquals(AppPreferencesManagerSingleton.PORTABLE_SCHEMA_VERSION,
                exported.getInt("schemaVersion"));
        assertFalse(exported.has("remainingBudgetSeconds"));
        assertFalse(exported.has("deactivationKey"));
        assertFalse(exported.has("isBlockerActive"));
        assertFalse(exported.has("pendingDeactivation"));
        assertEquals(48 * 60, exported.getInt("deactivationCooldownMinutes"));
        assertEquals(3, exported.getInt("deactivationWindowHours"));

        preferences.setRestrictedUrls(List.of("changed.test"));
        preferences.importPortableState(exported);
        assertEquals(List.of("example.com", "keyword:shorts"), preferences.getRestrictedUrls());
        assertTrue(preferences.isStrictRestrictedUrlPattern("example.com"));
        assertEquals(List.of("app.one"), preferences.getRestrictedAppPackages());
        assertTrue(preferences.isStrictRestrictedApp("app.one"));
        assertEquals(7_200, preferences.getDailyAllowanceSeconds());
        assertEquals(45, preferences.getBaseWaitTimeSeconds());
        assertEquals(.8f, preferences.getReentryGrowth(), .0001f);
        assertEquals(123, preferences.getRemainingBudgetSeconds());
        assertTrue(preferences.getIsBlockerActive());
        assertEquals(48 * 60, preferences.getDeactivationCooldownMinutes());
        assertEquals(3, preferences.getDeactivationWindowHours());
    }

    @Test
    public void portableImportSupportsLegacySchemaAndRejectsUnknownVersions() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("schemaVersion", 1)
                .put("forbiddenUrls", new JSONArray(List.of("legacy.test")))
                .put("extractiveApps", new JSONArray(List.of("legacy.app")))
                .put("dailyAllowanceUnits", 500)
                .put("baseWaitTimeSeconds", 0)
                .put("settingsLockEnabled", true);
        preferences.importPortableState(legacy);
        assertEquals(List.of("legacy.test"), preferences.getRestrictedUrls());
        assertEquals(List.of("legacy.app"), preferences.getRestrictedAppPackages());
        assertEquals(500, preferences.getDailyAllowanceSeconds());
        assertEquals(1, preferences.getBaseWaitTimeSeconds());
        assertTrue(preferences.isUninstallGuardEnabled());

        assertThrows(JSONException.class, () -> preferences.importPortableState(
                new JSONObject().put("schemaVersion", 0)));
        assertThrows(JSONException.class, () -> preferences.importPortableState(
                new JSONObject().put("schemaVersion", 99)));
        JSONObject invalidTiming = preferences.exportPortableState()
                .put("deactivationCooldownMinutes", 5);
        assertThrows(JSONException.class, () -> preferences.importPortableState(invalidTiming));
    }

    @Test
    public void portableImportRejectsIneffectiveRulesWithoutChangingExistingState() throws Exception {
        preferences.setRestrictedUrls(List.of("keep.example"));
        preferences.setRestrictedApps(List.of());

        JSONObject invalidUrl = preferences.exportPortableState()
                .put("restrictedUrls", new JSONArray(List.of("not-a-domain")));
        assertThrows(JSONException.class, () -> preferences.importPortableState(invalidUrl));
        assertEquals(List.of("keep.example"), preferences.getRestrictedUrls());

        JSONObject criticalApp = preferences.exportPortableState()
                .put("restrictedApps", new JSONArray(List.of("com.android.settings")));
        assertThrows(JSONException.class, () -> preferences.importPortableState(criticalApp));
        assertEquals(List.of(), preferences.getRestrictedAppPackages());

        JSONObject missingApp = preferences.exportPortableState()
                .put("restrictedApps", new JSONArray(List.of("missing.app")));
        assertThrows(JSONException.class, () -> preferences.importPortableState(missingApp));
        assertEquals(List.of(), preferences.getRestrictedAppPackages());
    }

    @Test
    public void corruptPrimaryRuleStorageFailsClosedWhileBlockerIsActive() throws Exception {
        preferences.setRestrictedUrls(List.of("blocked.example"));
        preferences.setRestrictedApps(List.of("app.one"));
        preferences.setIsBlockerActive(true);
        portable().edit()
                .putString("restricted_url_list", "corrupt bytes")
                .putString("restricted_app_list", "corrupt bytes")
                .commit();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);

        assertTrue(preferences.isRestrictedApp("com.example.unlisted"));
        assertTrue(preferences.isStrictRestrictedApp("com.example.unlisted"));
        assertFalse(preferences.isRestrictedApp("com.android.settings"));
        String failClosedPattern = preferences.findRestrictedUrlPattern(
                "https://unlisted.example/page");
        assertTrue(UrlPatternMatcher.matches(
                "https://unlisted.example/page", failClosedPattern));

        // Active-mode edits cannot silently replace the fail-safe state.
        preferences.setRestrictedApps(List.of("app.one"));
        preferences.setRestrictedUrls(List.of("blocked.example"));
        assertTrue(preferences.isRestrictedApp("com.example.unlisted"));

        // Once legitimately deactivated, saving valid rules repairs the storage.
        preferences.setIsBlockerActive(false);
        preferences.setRestrictedApps(List.of("app.one"));
        preferences.setRestrictedUrls(List.of("blocked.example"));
        assertFalse(preferences.isRestrictedApp("com.example.unlisted"));
        assertEquals(null, preferences.findRestrictedUrlPattern(
                "https://unlisted.example/page"));
    }

    @Test
    public void corruptStrictRuleStorageMakesSurvivingRulesStrict() throws Exception {
        preferences.setRestrictedUrls(List.of("blocked.example"));
        preferences.setRestrictedApps(List.of("app.one"));
        preferences.setIsBlockerActive(true);
        portable().edit()
                .putString("strict_restricted_url_list", "[bad")
                .putString("strict_restricted_app_list", "[bad")
                .commit();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);

        assertTrue(preferences.isStrictRestrictedApp("app.one"));
        assertFalse(preferences.isRestrictedApp("com.example.unlisted"));
        assertEquals(List.of("blocked.example"),
                preferences.getStrictRestrictedUrlsSnapshot());
        BrowserUrlEnforcementPolicy.RuleMatch match =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                        "blocked.example", "blocked.example", false,
                        preferences.getStrictRestrictedUrlsSnapshot(),
                        preferences.getRestrictedUrlsSnapshot());
        assertTrue(match != null && match.strict);
    }

    @Test
    public void pendingDeactivationIsLocalPersistentAndClearedByActivation() throws Exception {
        DeactivationPolicyEngine.Request request = new DeactivationPolicyEngine.Request(
                "request-id", 1000, 2000, 3, 6000, 1000);
        preferences.savePendingDeactivation(request);
        DeactivationPolicyEngine.Request restored = preferences.getPendingDeactivation();
        assertEquals("request-id", restored.id);
        assertEquals(6000, restored.cooldownMs);
        assertFalse(preferences.exportPortableState().has("pendingDeactivation"));
        preferences.finishPendingDeactivation(DeactivationPolicyEngine.State.INVALIDATED);
        assertEquals(DeactivationPolicyEngine.State.INVALIDATED,
                preferences.getDeactivationTerminalState());
        preferences.savePendingDeactivation(request);
        assertEquals(null, preferences.getDeactivationTerminalState());
        preferences.setIsBlockerActive(true);
        assertEquals(null, preferences.getPendingDeactivation());
    }

    @Test
    public void deactivationTimingDefaultsAndValidationAreFailClosed() {
        assertEquals(24 * 60, preferences.getDeactivationCooldownMinutes());
        assertEquals(1, preferences.getDeactivationWindowHours());
        preferences.setDeactivationCooldownMinutes(0);
        preferences.setDeactivationWindowHours(24);
        assertEquals(0, preferences.getDeactivationCooldownMinutes());
        preferences.setDeactivationCooldownMinutes(1);
        assertEquals(1, preferences.getDeactivationCooldownMinutes());
        assertEquals(24, preferences.getDeactivationWindowHours());
        assertThrows(IllegalArgumentException.class,
                () -> preferences.setDeactivationCooldownMinutes(5));
        assertThrows(IllegalArgumentException.class,
                () -> preferences.setDeactivationWindowHours(0));
    }

    @Test
    public void legacyCsvPreferencesMigrateWithoutDuplicatingOrKeepingDebt() throws Exception {
        clearAllPreferences();
        global().edit()
                .putString("forbidden_url_list", " one.test, two.test,one.test ")
                .putString("extractive_app_list", "app.one,app.two")
                .putInt("daily_allowance_units", 100)
                .putLong("remaining_potential_units", -50)
                .commit();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);

        assertEquals(List.of("one.test", "two.test"), preferences.getRestrictedUrls());
        assertEquals(List.of("app.one", "app.two"), preferences.getRestrictedAppPackages());
        assertEquals(100, preferences.getDailyAllowanceSeconds());
        assertEquals(0, preferences.getRemainingBudgetSeconds());
        assertFalse(global().contains("remaining_potential_units"));
        assertFalse(global().contains("forbidden_url_list"));
    }

    private SharedPreferences global() {
        return application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE);
    }

    private SharedPreferences portable() {
        return application.getSharedPreferences("portable_preferences", Context.MODE_PRIVATE);
    }

    private void clearAllPreferences() {
        application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences("portable_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences("display_recovery_state", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private void installPackage(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        Shadows.shadowOf(application.getPackageManager()).installPackage(packageInfo);
    }

    private static void resetSingleton() throws Exception {
        Field field = AppPreferencesManagerSingleton.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
