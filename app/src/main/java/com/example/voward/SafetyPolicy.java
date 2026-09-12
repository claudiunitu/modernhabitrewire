package com.example.voward;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.telecom.TelecomManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Packages that must remain reachable for emergency, calls, core UI, and recovery. */
public final class SafetyPolicy {
    /** AOSP names present on every build. OEM equivalents come from {@link #criticalPackages}. */
    private static final Set<String> BASELINE_CRITICAL_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.dialer",
            "com.android.emergency",
            "com.android.packageinstaller",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.dialer",
            "com.google.android.permissioncontroller"
    ));

    /** Emergency dialer action. The public constant is @hide; the action string is not. */
    private static final String ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY";
    /** Runtime-permission grant dialog. {@code PackageManager.ACTION_REQUEST_PERMISSIONS}. */
    private static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";

    private static volatile Set<String> resolvedCriticalPackages;

    private SafetyPolicy() {}

    /**
     * Exact membership test. {@code resolvedCriticalPackages} must come from
     * {@link #criticalPackages(Context)}. Substring matching is deliberately absent: it made
     * every package containing "emergency" unprotectable while still missing OEM dialers that
     * do not end in ".dialer".
     */
    public static boolean isCriticalPackage(String packageName, String ownPackage,
                                            Set<String> resolvedCriticalPackages) {
        if (packageName == null) return true;
        String value = packageName.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return true;
        if (ownPackage != null
                && value.equals(ownPackage.trim().toLowerCase(Locale.ROOT))) return true;
        if (BASELINE_CRITICAL_PACKAGES.contains(value)) return true;
        return resolvedCriticalPackages != null && resolvedCriticalPackages.contains(value);
    }

    /** Single-check convenience. Loops should hoist {@link #criticalPackages(Context)} instead. */
    public static boolean isCriticalPackage(Context context, String packageName) {
        return isCriticalPackage(packageName, context.getPackageName(), criticalPackages(context));
    }

    /**
     * Packages resolved from the platform rather than guessed from their names: the dialers,
     * the emergency dialer, Settings, and the permission controller as this device names them.
     * Cached for the life of the process; {@link #invalidate()} drops the cache.
     */
    public static Set<String> criticalPackages(Context context) {
        Set<String> cached = resolvedCriticalPackages;
        if (cached != null) return cached;
        cached = resolve(context);
        resolvedCriticalPackages = cached;
        return cached;
    }

    /** Call when the installed package set changes; the next read re-resolves. */
    public static void invalidate() {
        resolvedCriticalPackages = null;
    }

    private static Set<String> resolve(Context context) {
        Set<String> resolved = new HashSet<>();
        PackageManager packageManager = context.getPackageManager();
        Uri tel = Uri.parse("tel:");
        addHandlers(resolved, packageManager, new Intent(Intent.ACTION_DIAL));
        addHandlers(resolved, packageManager, new Intent(Intent.ACTION_DIAL, tel));
        addHandlers(resolved, packageManager, new Intent(Intent.ACTION_CALL, tel));
        addHandlers(resolved, packageManager, new Intent(ACTION_DIAL_EMERGENCY, tel));
        addHandlers(resolved, packageManager, new Intent(Settings.ACTION_SETTINGS));

        TelecomManager telecom =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecom != null) add(resolved, telecom.getDefaultDialerPackage());
        resolved.addAll(permissionDialogPackages(context));
        return Collections.unmodifiableSet(resolved);
    }

    /**
     * Packages that own the runtime-permission grant dialog. It draws over whichever app
     * asked for the permission, so the firewall must read it as an overlay rather than as a
     * foreground app change.
     */
    static Set<String> permissionDialogPackages(Context context) {
        Set<String> resolved = new HashSet<>();
        addHandlers(resolved, context.getPackageManager(),
                new Intent(ACTION_REQUEST_PERMISSIONS));
        return resolved;
    }

    private static void addHandlers(Set<String> target, PackageManager packageManager,
                                    Intent intent) {
        List<ResolveInfo> handlers = packageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo != null) add(target, handler.activityInfo.packageName);
        }
    }

    private static void add(Set<String> target, String packageName) {
        if (packageName == null) return;
        String value = packageName.trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty()) target.add(value);
    }
}
