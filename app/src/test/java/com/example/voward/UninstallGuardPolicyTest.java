package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UninstallGuardPolicyTest {

    private static final UninstallGuardPolicy.ScreenEvidence TARGET_ONLY =
            new UninstallGuardPolicy.ScreenEvidence(true, false, false, false, false);

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
    public void protectsDeviceAdminDeactivationAndOnlyAccessibilityServiceDetails() {
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
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.Settings$AccessibilitySettingsActivity",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.samsung.android.settings",
                        "com.samsung.android.settings.accessibility.base.widget.AccessibilityDashboardActivity",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.Settings$DeviceAdminSettingsActivity",
                        TARGET_ONLY));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
                        new UninstallGuardPolicy.ScreenEvidence(false, false, false, true, false)));
    }

    @Test
    public void accessibilityProtectionDoesNotDependOnOptionalUninstallGuard() {
        assertTrue(UninstallGuardPolicy.shouldBlock(
                UninstallGuardPolicy.GuardTarget.ACCESSIBILITY, false));
        assertFalse(UninstallGuardPolicy.shouldBlock(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS, false));
        assertFalse(UninstallGuardPolicy.shouldBlock(
                UninstallGuardPolicy.GuardTarget.DEVICE_ADMIN, false));
        assertTrue(UninstallGuardPolicy.shouldBlock(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS, true));
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
                        new UninstallGuardPolicy.ScreenEvidence(true, true, false, false, false)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.DEVICE_ADMIN,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        genericClass,
                        new UninstallGuardPolicy.ScreenEvidence(true, false, true, false, false)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.ACCESSIBILITY,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        genericClass,
                        new UninstallGuardPolicy.ScreenEvidence(true, false, false, true, false)));
    }

    @Test
    public void recognizesRomanianGuardLabelsWithOrWithoutDiacritics() {
        assertTrue(UninstallGuardPolicy.isAppControlSignal("Dezinstalați"));
        assertTrue(UninstallGuardPolicy.isAppControlSignal("Oprește forțat"));
        assertTrue(UninstallGuardPolicy.isAppControlSignal("Stergeti datele"));
        assertTrue(UninstallGuardPolicy.isDeviceAdminSignal(
                "Dezactivați administratorul dispozitivului"));
    }

    @Test
    public void mainAccessibilityDashboardSwitchIsNotServiceDetailEvidence() {
        String label = "voward protection service";

        assertFalse(UninstallGuardPolicy.isTargetAccessibilityControl(
                label,
                "com.android.settings:id/switch_widget",
                label));
        assertFalse(UninstallGuardPolicy.isTargetAccessibilityControl(
                "Use " + label,
                "com.android.settings:id/switch_widget",
                label));
        assertTrue(UninstallGuardPolicy.isTargetAccessibilityControl(
                label,
                "com.android.settings:id/service_switch",
                label));
    }

    @Test
    public void stableResourceIdsDoNotDependOnTranslatedCaptions() {
        assertTrue(UninstallGuardPolicy.isAppControlSignal(
                "Vider le cache",
                "com.android.settings:id/clear_cache"));
        assertTrue(UninstallGuardPolicy.isAppControlSignal(
                "Speicherinhalt loeschen",
                "com.android.settings:id/clear_storage"));
        assertTrue(UninstallGuardPolicy.isDeviceAdminSignal(
                "Desactiver cette application",
                "com.android.settings:id/restricted_action"));
        assertTrue(UninstallGuardPolicy.isTargetAccessibilityControl(
                "Utiliser voward protection service",
                "com.android.settings:id/switch_text",
                "voward protection service"));
    }

    @Test
    public void genericLocalizedAppInfoCanUseLanguageIndependentActionLayout() {
        assertEquals(
                UninstallGuardPolicy.GuardTarget.APP_CONTROLS,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.SubSettings",
                        new UninstallGuardPolicy.ScreenEvidence(
                                true, false, false, false, true)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.SubSettings",
                        new UninstallGuardPolicy.ScreenEvidence(
                                false, false, false, false, true)));
    }

    @Test
    public void neverBlocksUnrelatedAppControlsWithoutVowardIdentity() {
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.android.settings",
                        "com.android.settings.applications.appinfo.AppInfoDashboardFragment",
                        new UninstallGuardPolicy.ScreenEvidence(false, true, true, true, true)));
        assertEquals(
                UninstallGuardPolicy.GuardTarget.NONE,
                UninstallGuardPolicy.classify(
                        "com.example.packageinstaller",
                        "com.android.packageinstaller.UninstallerActivity",
                        TARGET_ONLY));
    }
}
