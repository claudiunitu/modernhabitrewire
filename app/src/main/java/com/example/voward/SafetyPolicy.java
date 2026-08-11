package com.example.voward;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Packages that must remain reachable for emergency, calls, core UI, and recovery. */
public final class SafetyPolicy {
    private static final Set<String> EXACT_CRITICAL_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.dialer",
            "com.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.dialer",
            "com.google.android.permissioncontroller"
    ));

    private SafetyPolicy() {}

    public static boolean isCriticalPackage(String packageName, String ownPackage) {
        if (packageName == null) return true;
        String value = packageName.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals(ownPackage.toLowerCase(Locale.ROOT))) return true;
        if (EXACT_CRITICAL_PACKAGES.contains(value)) return true;
        return value.contains("emergency") || value.contains("permissioncontroller")
                || value.endsWith(".dialer") || value.endsWith(".telecom");
    }
}
