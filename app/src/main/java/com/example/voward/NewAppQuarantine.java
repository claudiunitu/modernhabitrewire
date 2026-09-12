package com.example.voward;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.Set;
import java.util.TreeSet;

/**
 * Pauses an app the first time it appears, so a rule can be written before it is used.
 *
 * <p>Section 11 counts this as the reason a replacement app — a clone, a web wrapper, the same
 * content under a package no rule names — costs one prompt instead of nothing. Without it, the
 * cheapest bypass of the whole design is installing something with a different name.</p>
 *
 * <p>It does not stop anything being installed. The install succeeds, from Play or from
 * anywhere else; the app is held for one decision and then it is the user's.</p>
 */
final class NewAppQuarantine {

    private static final String TAG = "NewAppQuarantine";

    private NewAppQuarantine() {}

    /**
     * Compares what is installed against what was installed last time and quarantines the
     * difference.
     *
     * <p>A snapshot diff rather than a broadcast, because {@code ACTION_PACKAGE_ADDED} cannot be
     * registered in the manifest and the runtime receiver lives inside the accessibility
     * service, which is being removed. The reconcile that calls this runs on boot, on every
     * session boundary, and every fifteen minutes, so the worst case is a new app usable for
     * one tick — and that is one tick of an app nothing has a rule for yet.</p>
     *
     * <p>Called before the desired state is computed, so anything found here is suspended by
     * the same reconcile rather than by the next one.</p>
     */
    static void sweep(Context context, AppPreferencesManagerSingleton preferences) {
        Set<String> installed = launchablePackages(context);
        Set<String> known = preferences.getKnownPackages();

        // First look on this install: everything already on the phone is the baseline, not a
        // hundred apps to review.
        if (known.isEmpty() || !preferences.isNewAppQuarantineEnabled()
                || !preferences.getIsBlockerActive()) {
            preferences.setKnownPackages(installed);
            return;
        }

        Set<String> fresh = new TreeSet<>(installed);
        fresh.removeAll(known);
        preferences.setKnownPackages(installed);
        if (fresh.isEmpty()) return;

        Set<String> criticalPackages = SafetyPolicy.criticalPackages(context);
        Set<String> browsers = BrowserPolicy.installedBrowsers(context);
        String ownPackage = context.getPackageName();
        Set<String> quarantine = new TreeSet<>();
        for (String packageName : fresh) {
            if (SafetyPolicy.isCriticalPackage(packageName, ownPackage, criticalPackages)) continue;
            // An app the user has already written a rule for is not an unknown quantity, and a
            // browser is sorted by the browser landscape instead.
            if (preferences.isRestrictedApp(packageName)) continue;
            if (browsers.contains(packageName)) continue;
            quarantine.add(packageName);
        }
        if (quarantine.isEmpty()) return;

        Log.i(TAG, "Quarantining " + quarantine.size() + " newly installed package(s)");
        preferences.addQuarantinedPackages(quarantine);
    }

    /** Everything with a launcher entry. An app with no icon is not one the user opens. */
    private static Set<String> launchablePackages(Context context) {
        Set<String> packages = new TreeSet<>();
        Intent launchable = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : context.getPackageManager()
                .queryIntentActivities(launchable, PackageManager.MATCH_ALL)) {
            if (info.activityInfo != null && info.activityInfo.packageName != null) {
                packages.add(info.activityInfo.packageName);
            }
        }
        return packages;
    }
}
