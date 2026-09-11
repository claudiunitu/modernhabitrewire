package com.example.voward;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.RestrictionsManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The only place in Voward that is allowed to call {@link DevicePolicyManager}.
 *
 * <p>Every policy is applied from one desired {@link EnforcementState} and cleared in one
 * fixed order. An {@code addUserRestriction} anywhere else would eventually be a restriction
 * that release does not know about, leaving the user with a locked device and no admin able
 * to unlock it.</p>
 *
 * <p>The applied state is mirrored into {@code setApplicationRestrictions(admin, self, ...)},
 * which system_server stores outside the app's data directory. That survives
 * {@code pm clear}, so wiping Voward's data cannot orphan a policy.</p>
 */
final class PolicyReconciler {

    private static final String TAG = "PolicyReconciler";

    private final Context appContext;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName admin;

    PolicyReconciler(Context context) {
        appContext = context.getApplicationContext();
        devicePolicyManager = (DevicePolicyManager)
                appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(appContext, MyDeviceAdminReceiver.class);
    }

    /** Device Owner, not the far weaker Device Admin the optional uninstall guard uses. */
    boolean isProvisioned() {
        return devicePolicyManager != null
                && devicePolicyManager.isDeviceOwnerApp(appContext.getPackageName());
    }

    /**
     * Brings the device to {@code desired} and records the mirror.
     *
     * <p>Idempotent, so a crash part way through heals on the next boot or job tick rather
     * than wedging. Returns false when nothing could be applied because Voward is not the
     * device owner.</p>
     */
    boolean reconcile(EnforcementState desired) {
        if (!isProvisioned()) return false;
        EnforcementState previous = mirroredState();
        if (desired.equals(previous)) return true;

        Log.i(TAG, "Reconciling " + previous + " -> " + desired);
        applyUserRestrictions(previous.userRestrictions, desired.userRestrictions);
        if (TamperPolicy.requiresAutomaticTime(desired.userRestrictions)) requireAutomaticTime();
        applySuspension(previous.suspendedPackages, desired.suspendedPackages);
        applyHiding(previous.hiddenPackages, desired.hiddenPackages);
        applyUninstallBlock(desired.uninstallBlocked);
        applyUserControlBlock(desired.uninstallBlocked);
        applyBrowserPolicy(previous.managedBrowsers, desired.managedBrowsers, desired.urlBlocklist);
        applySupportMessage(true);
        writeMirror(desired);
        return true;
    }

    /**
     * Clears everything, in the order section 6.2 of the hardening plan fixes.
     *
     * <p>Factory-reset protection goes first and the factory-reset restriction goes with the
     * other user restrictions, both before the admin itself can be given up. Clearing the
     * device owner while either is still set would leave the user holding a restriction with
     * nothing left that can remove it.</p>
     */
    boolean release() {
        if (!isProvisioned()) {
            writeReleasedMirrorIfPossible();
            return false;
        }
        EnforcementState previous = mirroredState();
        Log.i(TAG, "Releasing from " + previous);

        clearFactoryResetProtection();
        applyUserRestrictions(previous.userRestrictions, EnforcementState.released().userRestrictions);
        applySuspension(previous.suspendedPackages, EnforcementState.released().suspendedPackages);
        applyHiding(previous.hiddenPackages, EnforcementState.released().hiddenPackages);
        applyUninstallBlock(false);
        applyUserControlBlock(false);
        // Every installed browser, not just the ones the mirror knows about: the browser test
        // flow writes a probe policy, and a browser that was rejected or uninstalled mid-run
        // would otherwise keep a policy release has no record of.
        Set<String> everyBrowser = new HashSet<>(previous.managedBrowsers);
        everyBrowser.addAll(BrowserPolicy.installedBrowsers(appContext));
        applyBrowserPolicy(everyBrowser, Collections.emptySet(), Collections.emptySet());
        stopRequiringAutomaticTime();
        applySupportMessage(false);
        writeMirror(EnforcementState.released());
        Log.i(TAG, "Release complete");
        return true;
    }

    /**
     * Release, then give up device ownership so a normal uninstall becomes possible.
     * There is no way back without a computer, so only the explicit "Remove Voward" action
     * may call this.
     */
    boolean removeDeviceOwner() {
        if (!isProvisioned()) return false;
        release();
        try {
            devicePolicyManager.clearDeviceOwnerApp(appContext.getPackageName());
            Log.i(TAG, "Device owner cleared");
            return true;
        } catch (SecurityException | IllegalStateException refused) {
            Log.e(TAG, "Could not clear device owner", refused);
            return false;
        }
    }

    /** The last state successfully applied, as recorded in system_server. */
    EnforcementState mirroredState() {
        RestrictionsManager restrictions = (RestrictionsManager)
                appContext.getSystemService(Context.RESTRICTIONS_SERVICE);
        if (restrictions == null) return EnforcementState.released();
        return EnforcementState.fromBundle(restrictions.getApplicationRestrictions());
    }

    private void writeMirror(EnforcementState state) {
        try {
            devicePolicyManager.setApplicationRestrictions(
                    admin, appContext.getPackageName(), state.toBundle());
        } catch (SecurityException | IllegalStateException refused) {
            Log.e(TAG, "Could not mirror enforcement state", refused);
        }
    }

    private void writeReleasedMirrorIfPossible() {
        if (devicePolicyManager == null) return;
        try {
            devicePolicyManager.setApplicationRestrictions(
                    admin, appContext.getPackageName(), EnforcementState.released().toBundle());
        } catch (SecurityException | IllegalStateException notAdmin) {
            // Expected when provisioning never happened; there is nothing to mirror.
            Log.d(TAG, "No admin rights to clear the mirror: " + notAdmin.getMessage());
        }
    }

    private void applyUserRestrictions(Set<String> previous, Set<String> desired) {
        for (String restriction : difference(previous, desired)) {
            try {
                devicePolicyManager.clearUserRestriction(admin, restriction);
                Log.i(TAG, "Cleared user restriction " + restriction);
            } catch (SecurityException | IllegalArgumentException refused) {
                Log.e(TAG, "Could not clear user restriction " + restriction, refused);
            }
        }
        for (String restriction : difference(desired, previous)) {
            try {
                devicePolicyManager.addUserRestriction(admin, restriction);
                Log.i(TAG, "Added user restriction " + restriction);
            } catch (SecurityException | IllegalArgumentException refused) {
                Log.e(TAG, "Could not add user restriction " + restriction, refused);
            }
        }
    }

    /** One batched call per direction: suspending package by package is a per-package IPC. */
    private void applySuspension(Set<String> previous, Set<String> desired) {
        setSuspended(difference(previous, desired), false);
        setSuspended(difference(desired, previous), true);
    }

    private void setSuspended(Set<String> packages, boolean suspended) {
        if (packages.isEmpty()) return;
        try {
            String[] failed = devicePolicyManager.setPackagesSuspended(
                    admin, packages.toArray(new String[0]), suspended);
            if (failed != null && failed.length > 0) {
                Log.w(TAG, "Suspension refused for " + failed.length + " package(s)");
            }
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not change suspension", refused);
        }
    }

    private void applyHiding(Set<String> previous, Set<String> desired) {
        setHidden(difference(previous, desired), false);
        setHidden(difference(desired, previous), true);
    }

    private void setHidden(Set<String> packages, boolean hidden) {
        for (String packageName : packages) {
            try {
                devicePolicyManager.setApplicationHidden(admin, packageName, hidden);
            } catch (SecurityException | IllegalArgumentException refused) {
                Log.e(TAG, "Could not change hidden state for " + packageName, refused);
            }
        }
    }

    /**
     * Writes one sentinel rule to a single browser so the user can see for themselves whether
     * it honours the policy. Section 4.4: a browser that declares nothing is unknown, not
     * unsupported, and a Chromium fork can enforce a key it never advertises.
     *
     * <p>The value is overwritten by the next reconcile, and release clears it from every
     * installed browser, so a probe cannot outlive the test that wrote it.</p>
     */
    boolean pushProbePolicy(String browser) {
        if (!isProvisioned() || browser == null || browser.isEmpty()) return false;
        writeBrowserPolicy(browser, Collections.singletonList(BrowserPolicy.PROBE_DOMAIN));
        return true;
    }

    /**
     * Puts the reconciled policy back over a sentinel left by {@link #pushProbePolicy}.
     *
     * <p>Separate from {@link #reconcile} on purpose: reconcile short-circuits when the desired
     * state already matches the mirror, and the probe is written outside the mirror, so a
     * verdict that changes nothing else would otherwise leave the sentinel in place.</p>
     */
    void restoreBrowserPolicy(String probedBrowser, EnforcementState desired) {
        if (!isProvisioned() || probedBrowser == null || probedBrowser.isEmpty()) return;
        applyBrowserPolicy(Collections.singleton(probedBrowser), desired.managedBrowsers,
                desired.urlBlocklist);
    }

    /**
     * Pushes the website rules into every managed browser and clears them from any browser
     * that has stopped being one, so an uninstalled or newly rejected browser does not keep a
     * policy nothing is tracking any more.
     */
    private void applyBrowserPolicy(Set<String> previous, Set<String> desired,
                                    Set<String> patterns) {
        for (String browser : difference(previous, desired)) {
            writeBrowserPolicy(browser, Collections.emptySet());
        }
        for (String browser : desired) {
            writeBrowserPolicy(browser, patterns);
        }
    }

    /**
     * Chromium reads {@code URLBlocklist} as one JSON array inside a single string, so this is
     * {@code putString} and not {@code putStringArray}. Verified against Brave; see 4.5.1.
     *
     * <p>{@code setApplicationRestrictions} replaces the whole bundle for that package. Voward
     * is the only device owner on a phone its user owns, so there is no other admin whose keys
     * this could be trampling.</p>
     */
    private void writeBrowserPolicy(String browser, Collection<String> patterns) {
        Bundle policy = new Bundle();
        policy.putString(BrowserPolicy.KEY_URL_BLOCKLIST, BrowserPolicy.toPolicyValue(patterns));
        try {
            devicePolicyManager.setApplicationRestrictions(admin, browser, policy);
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not push website rules to " + browser, refused);
        }
    }

    /**
     * Force-stop and clear-data from Settings, which are the two ways to knock Voward over
     * without uninstalling it. Both are refused for a package the device owner names here.
     */
    private void applyUserControlBlock(boolean blocked) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            devicePolicyManager.setUserControlDisabledPackages(admin, blocked
                    ? Collections.singletonList(appContext.getPackageName())
                    : Collections.emptyList());
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not change the user-control block", refused);
        }
    }

    /**
     * Pins the clock to network time. {@code DISALLOW_CONFIG_DATE_TIME} only refuses manual
     * changes; without this the device would keep whatever wrong time it was last left on and
     * the daily allowance would be computed from it.
     */
    private void requireAutomaticTime() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                devicePolicyManager.setAutoTimeEnabled(admin, true);
            } else {
                setAutoTimeRequiredLegacy(true);
            }
        } catch (SecurityException | IllegalStateException refused) {
            Log.e(TAG, "Could not require automatic time", refused);
        }
    }

    /**
     * Release lifts the requirement but does not switch network time back off. Leaving the
     * clock correct costs the user nothing; taking it away from them again is a change they
     * never asked for.
     */
    private void stopRequiringAutomaticTime() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return;
        try {
            setAutoTimeRequiredLegacy(false);
        } catch (SecurityException | IllegalStateException refused) {
            Log.e(TAG, "Could not stop requiring automatic time", refused);
        }
    }

    @SuppressWarnings("deprecation") // setAutoTimeEnabled replaces it, but only from API 30.
    private void setAutoTimeRequiredLegacy(boolean required) {
        devicePolicyManager.setAutoTimeRequired(admin, required);
    }

    private void applyUninstallBlock(boolean blocked) {
        try {
            devicePolicyManager.setUninstallBlocked(admin, appContext.getPackageName(), blocked);
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not change the uninstall block", refused);
        }
    }

    /**
     * Names Voward on the "blocked by your admin" screens Settings shows, and — verified on
     * Samsung API 30 — inside the paused-app dialog itself, which is the only explanation the
     * user gets at the moment they tap a suspended icon. Worth writing for that alone.
     */
    private void applySupportMessage(boolean active) {
        CharSequence message = active
                ? appContext.getString(R.string.suspended_support_message) : null;
        try {
            devicePolicyManager.setShortSupportMessage(admin, message);
            devicePolicyManager.setLongSupportMessage(admin, message);
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not set the support message", refused);
        }
    }

    private void clearFactoryResetProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            devicePolicyManager.setFactoryResetProtectionPolicy(admin, null);
            Log.i(TAG, "Factory reset protection cleared");
        } catch (SecurityException | UnsupportedOperationException | IllegalStateException e) {
            // Not every device implements FRP policy. Nothing sets it either, so a device
            // that cannot clear it is not carrying one.
            Log.d(TAG, "Factory reset protection not clearable here: " + e.getMessage());
        }
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        if (from.isEmpty()) return from;
        Set<String> result = new HashSet<>(from);
        result.removeAll(without);
        return result;
    }
}
