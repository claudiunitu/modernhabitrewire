package com.example.voward;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;

/**
 * Pure policy used by the accessibility service to classify system screens that can
 * disable or remove Voward. Keeping classification independent from Android UI nodes
 * makes the conservative matching rules unit-testable.
 */
final class UninstallGuardPolicy {

    enum GuardTarget {
        NONE,
        APP_CONTROLS,
        DEVICE_ADMIN,
        ACCESSIBILITY
    }

    static final class ScreenEvidence {
        final boolean targetVisible;
        final boolean appControlVisible;
        final boolean deviceAdminControlVisible;
        final boolean accessibilityControlVisible;
        final boolean appInfoActionLayoutVisible;

        ScreenEvidence(
                boolean targetVisible,
                boolean appControlVisible,
                boolean deviceAdminControlVisible,
                boolean accessibilityControlVisible,
                boolean appInfoActionLayoutVisible) {
            this.targetVisible = targetVisible;
            this.appControlVisible = appControlVisible;
            this.deviceAdminControlVisible = deviceAdminControlVisible;
            this.accessibilityControlVisible = accessibilityControlVisible;
            this.appInfoActionLayoutVisible = appInfoActionLayoutVisible;
        }
    }

    private static final String[] APP_CONTROL_SIGNALS = {
            // AOSP text and stable resource-id fragments.
            "uninstall", "delete app", "remove app", "force stop", "force_stop",
            "clear data", "clear storage", "clear_data", "clear_storage",
            // Romanian Settings translations. Matching is accent-insensitive so these
            // cover both OEM wording and devices configured without Romanian diacritics.
            "dezinstal", "sterge aplicatia", "elimina aplicatia",
            "oprire fortata", "opreste fortat", "fortati oprirea",
            "sterge datele", "stergeti datele", "sterge spatiul de stocare",
            "stergeti spatiul de stocare"
    };

    private static final String[] DEVICE_ADMIN_SIGNALS = {
            "deactivate", "remove device admin", "device administrator",
            "device_admin", "device admin", "dezactiv", "administrator dispozitiv",
            "administratorul dispozitivului", "administrare a dispozitivului"
    };

    private static final String[] ACCESSIBILITY_SERVICE_SIGNALS = {
            "use service", "use_service", "service toggle", "service_toggle",
            "installed service", "installed_service", "foloseste serviciul",
            "folositi serviciul", "utilizeaza serviciul", "utilizati serviciul",
            "serviciu instalat"
    };

    private static final Set<String> GUARD_HOST_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.android.settings",
                    "com.google.android.settings",
                    "com.samsung.android.settings",
                    "com.android.packageinstaller",
                    "com.google.android.packageinstaller",
                    "com.samsung.android.packageinstaller",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller",
                    "com.android.vending",
                    "com.miui.packageinstaller",
                    "com.miui.securitycenter",
                    "com.coloros.safecenter",
                    "com.oplus.safecenter",
                    "com.huawei.systemmanager",
                    "com.huawei.appmarket",
                    "com.vivo.permissionmanager",
                    "com.iqoo.secure"
            )));

    private static final String[] APP_CONTROL_CLASS_FRAGMENTS = {
            "appinfodashboard",
            "installedappdetails",
            "appinfobase",
            "appstoragesettings",
            "uninstalleractivity",
            "uninstallappprogress",
            "deletepackage",
            "deletepackagesactivity"
    };

    private static final String[] DEVICE_ADMIN_CLASS_FRAGMENTS = {
            "deviceadminadd",
            "deviceadminsettings",
            "deviceadmindetails",
            "deviceadminwarning"
    };

    private static final String[] ACCESSIBILITY_DETAIL_CLASS_FRAGMENTS = {
            "accessibilitydetails",
            "toggleaccessibilityservice",
            "accessibilityservicewarning",
            "accessibilityservicedetails"
    };

    private UninstallGuardPolicy() {}

    static boolean isGuardHostPackage(String packageName) {
        if (packageName == null) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        if (GUARD_HOST_PACKAGES.contains(normalized)) return true;

        // Package installers are commonly renamed by OEMs. Limit the fallback to
        // system/vendor namespaces so an arbitrary third-party package cannot cause
        // Voward to navigate away from its UI.
        boolean trustedNamespace = normalized.startsWith("com.android.")
                || normalized.startsWith("com.google.android.")
                || normalized.startsWith("com.samsung.android.")
                || normalized.startsWith("com.miui.")
                || normalized.startsWith("com.coloros.")
                || normalized.startsWith("com.oplus.")
                || normalized.startsWith("com.huawei.")
                || normalized.startsWith("com.vivo.");
        return trustedNamespace
                && (normalized.contains("packageinstaller")
                || normalized.endsWith(".settings")
                || normalized.contains("permissioncontroller"));
    }

    static GuardTarget classify(
            String packageName,
            String className,
            ScreenEvidence evidence) {
        if (!isGuardHostPackage(packageName) || evidence == null) {
            return GuardTarget.NONE;
        }

        String normalizedClass = className == null
                ? ""
                : className.toLowerCase(Locale.ROOT);

        if (!evidence.targetVisible) return GuardTarget.NONE;

        // Only service-detail classes are intrinsically dangerous. A general
        // AccessibilitySettings/Dashboard class may legitimately show Voward's row
        // alongside every other service and must remain navigable.
        if (containsAny(normalizedClass, ACCESSIBILITY_DETAIL_CLASS_FRAGMENTS)) {
            return GuardTarget.ACCESSIBILITY;
        }
        if (containsAny(normalizedClass, DEVICE_ADMIN_CLASS_FRAGMENTS)) {
            return GuardTarget.DEVICE_ADMIN;
        }
        if (containsAny(normalizedClass, APP_CONTROL_CLASS_FRAGMENTS)) {
            return GuardTarget.APP_CONTROLS;
        }

        // Many Android and OEM Settings versions expose only a generic SubSettings
        // activity class. In that case require a visible, category-specific control
        // as well as Voward's identity before treating the screen as dangerous.
        if (evidence.accessibilityControlVisible) return GuardTarget.ACCESSIBILITY;
        if (evidence.deviceAdminControlVisible) return GuardTarget.DEVICE_ADMIN;
        if (evidence.appControlVisible) return GuardTarget.APP_CONTROLS;
        // Some OEM app-info pages use generic activity classes and generic button ids
        // (button1/button2/button3), leaving translated button captions as the only text.
        // A target header plus the characteristic multi-action layout is a conservative,
        // language-independent fallback. A normal app list row does not expose this layout.
        if (evidence.appInfoActionLayoutVisible) return GuardTarget.APP_CONTROLS;
        return GuardTarget.NONE;
    }

    static boolean shouldBlock(GuardTarget target, boolean uninstallGuardEnabled) {
        if (target == null || target == GuardTarget.NONE) return false;
        // The accessibility service is the enforcement mechanism, so its controls
        // must remain protected whenever the blocker is active. The optional guard
        // continues to control only uninstall/app-info and Device Admin friction.
        return target == GuardTarget.ACCESSIBILITY || uninstallGuardEnabled;
    }

    private static boolean containsAny(String value, String[] fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    static boolean isAppControlSignal(String value) {
        return containsNormalizedSignal(value, APP_CONTROL_SIGNALS);
    }

    static boolean isDeviceAdminSignal(String value) {
        return containsNormalizedSignal(value, DEVICE_ADMIN_SIGNALS);
    }

    static boolean isAccessibilityServiceSignal(String value) {
        return containsNormalizedSignal(value, ACCESSIBILITY_SERVICE_SIGNALS);
    }

    private static boolean containsNormalizedSignal(String value, String[] signals) {
        if (value == null || value.isEmpty()) return false;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return containsAny(normalized, signals);
    }
}
