package com.example.voward;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sweep is the only thing standing between a rule set and a freshly installed replacement
 * app, and it could not be exercised on either test device, so it is covered here instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NewAppQuarantineTest {

    private Application application;
    private AppPreferencesManagerSingleton preferences;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences("portable_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        SafetyPolicy.invalidate();
        BrowserPolicy.invalidate();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);
        preferences.setIsBlockerActive(true);
        preferences.setNewAppQuarantineEnabled(true);
    }

    @Test
    public void firstSweepTakesWhatIsInstalledAsTheBaseline() {
        installLaunchable("app.one");
        installLaunchable("app.two");

        NewAppQuarantine.sweep(application, preferences);

        assertTrue(preferences.getQuarantinedPackages().isEmpty());
        assertTrue(preferences.getKnownPackages().containsAll(Set.of("app.one", "app.two")));
    }

    @Test
    public void anAppThatAppearsAfterTheBaselineIsHeldForOneDecision() {
        installLaunchable("app.one");
        NewAppQuarantine.sweep(application, preferences);

        installLaunchable("app.replacement");
        NewAppQuarantine.sweep(application, preferences);

        assertEquals(Set.of("app.replacement"), preferences.getQuarantinedPackages());
        assertTrue(preferences.getKnownPackages().contains("app.replacement"));
    }

    @Test
    public void anAppTheUserAlreadyWroteARuleForIsNotAnUnknownQuantity() {
        installLaunchable("app.one");
        NewAppQuarantine.sweep(application, preferences);
        preferences.addRestrictedAppPackage("app.known", false);

        installLaunchable("app.known");
        NewAppQuarantine.sweep(application, preferences);

        assertTrue(preferences.getQuarantinedPackages().isEmpty());
    }

    @Test
    public void aNewBrowserIsSortedByTheBrowserLandscapeInstead() {
        installLaunchable("app.one");
        NewAppQuarantine.sweep(application, preferences);

        installLaunchable("browser.new");
        makeBrowser("browser.new");
        BrowserPolicy.invalidate();
        NewAppQuarantine.sweep(application, preferences);

        assertTrue(preferences.getQuarantinedPackages().isEmpty());
    }

    @Test
    public void nothingIsHeldWhileProtectionIsOffOrTheFeatureIsDisabled() {
        installLaunchable("app.one");
        NewAppQuarantine.sweep(application, preferences);

        preferences.setIsBlockerActive(false);
        installLaunchable("app.two");
        NewAppQuarantine.sweep(application, preferences);
        assertTrue(preferences.getQuarantinedPackages().isEmpty());
        // The baseline still moves, so switching protection on does not review the whole phone.
        assertTrue(preferences.getKnownPackages().contains("app.two"));

        preferences.setIsBlockerActive(true);
        preferences.setNewAppQuarantineEnabled(false);
        installLaunchable("app.three");
        NewAppQuarantine.sweep(application, preferences);
        assertTrue(preferences.getQuarantinedPackages().isEmpty());
        assertTrue(preferences.getKnownPackages().contains("app.three"));
    }

    @Test
    public void anAppWithNoLauncherEntryIsNotSomethingTheUserOpens() {
        installLaunchable("app.one");
        NewAppQuarantine.sweep(application, preferences);

        installPackage("service.only");
        NewAppQuarantine.sweep(application, preferences);

        assertTrue(preferences.getQuarantinedPackages().isEmpty());
        assertFalse(preferences.getKnownPackages().contains("service.only"));
    }

    private void installPackage(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        Shadows.shadowOf(application.getPackageManager()).installPackage(packageInfo);
    }

    private void installLaunchable(String packageName) {
        installPackage(packageName);
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        addActivity(packageName, ".Main", launcher);
    }

    private void makeBrowser(String packageName) {
        IntentFilter browsable = new IntentFilter(Intent.ACTION_VIEW);
        browsable.addCategory(Intent.CATEGORY_BROWSABLE);
        browsable.addCategory(Intent.CATEGORY_DEFAULT);
        browsable.addDataScheme("http");
        browsable.addDataScheme("https");
        addActivity(packageName, ".Browser", browsable);
    }

    private void addActivity(String packageName, String activity, IntentFilter filter) {
        ShadowPackageManager shadow = Shadows.shadowOf(application.getPackageManager());
        ComponentName component = new ComponentName(packageName, packageName + activity);
        shadow.addActivityIfNotPresent(component);
        shadow.addIntentFilterForActivity(component, filter);
    }

    private static void resetSingleton() throws Exception {
        Field field = AppPreferencesManagerSingleton.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
