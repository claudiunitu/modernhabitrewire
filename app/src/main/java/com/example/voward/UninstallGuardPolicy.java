package com.example.voward;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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

        ScreenEvidence(
                boolean targetVisible,
                boolean appControlVisible,
                boolean deviceAdminControlVisible,
                boolean accessibilityControlVisible) {
            this.targetVisible = targetVisible;
            this.appControlVisible = appControlVisible;
            this.deviceAdminControlVisible = deviceAdminControlVisible;
            this.accessibilityControlVisible = accessibilityControlVisible;
        }
    }

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
}
