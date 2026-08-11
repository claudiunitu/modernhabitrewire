package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UninstallGuardPolicyTest {

    private static final UninstallGuardPolicy.ScreenEvidence TARGET_ONLY =
            new UninstallGuardPolicy.ScreenEvidence(true, false, false, false);

    @Test
    public void recognizesAndroidAndOemGuardHostsButRejectsThirdPartyImpostors() {
        assertTrue(UninstallGuardPolicy.isGuardHostPackage("com.android.settings"));
        assertTrue(UninstallGuardPolicy.isGuardHostPackage("com.miui.packageinstaller"));
        assertTrue(UninstallGuardPolicy.isGuardHostPackage(
                "com.samsung.android.custompackageinstaller"));
        assertFalse(UninstallGuardPolicy.isGuardHostPackage("com.example.packageinstaller"));
    }

    @Test
    public void protectsVowardAppInfoAndUninstallConfirmation() {
        assertEquals(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.applications.appinfo.AppInfoDashboardFragment",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS,
                UninstallGuardPolicy.classify(
                        "com.google.android.packageinstaller",
                        "com.android.packageinstaller.UninstallerActivity",
                        TARGET_ONLY));
    }

    @Test
    public void protectsDeviceAdminDeactivationAndAccessibilitySettings() {
        assertEquals(
                UninstallGuardPolicy.GuardTarget.DEVICE_ADMIN,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.DeviceAdminAdd",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.ACCESSIBILITY,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.accessibility.AccessibilityDetailsSettings",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.ACCESSIBILITY,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity",
                        TARGET_ONLY));
    }

    @Test
    public void genericOemSubSettingsRequiresTargetAndCategoryEvidence() {
        String genericClass = "com.android.settings.SubSettings";

        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings", genericClass, TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        genericClass,
                        new UninstallGuardPolicy.ScreenEvidence(true, true, false, false)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.DEVICE_ADMIN,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        genericClass,
                        new UninstallGuardPolicy.ScreenEvidence(true, false, true, false)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.ACCESSIBILITY,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        genericClass,
                        new UninstallGuardPolicy.ScreenEvidence(true, false, false, true)));
    }

    @Test
    public void neverBlocksAnUnrelatedAppOrScreenWithoutVowardIdentity() {
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.DeviceAdminAdd",
                        new UninstallGuardPolicy.ScreenEvidence(false, true, true, true)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.example.packageinstaller",
                        "com.android.packageinstaller.UninstallerActivity",
                        TARGET_ONLY));
    }
}
