package com.example.voward;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.RestrictionsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

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
        if (desired.matchesApplied(previous) && !anyRefusedPackageIsInstalled(previous)) {
            return true;
        }

        Log.i(TAG, "Reconciling " + previous + " -> " + desired);
        applyUserRestrictions(previous.userRestrictions, desired.userRestrictions);
        if (TamperPolicy.requiresAutomaticTime(desired.userRestrictions)) requireAutomaticTime();
        Set<String> suspended = applySuspension(previous.suspendedPackages,
                desired.suspendedPackages);
        Set<String> hidden = applyHiding(previous.hiddenPackages, desired.hiddenPackages);
        applyUninstallBlock(desired.uninstallBlocked);
        applyUserControlBlock(desired.uninstallBlocked);
        applyBrowserPolicy(previous.managedBrowsers, desired.managedBrowsers, desired.urlBlocklist);
        applySupportMessage(true);
        // What was applied, not what was asked for: a package the platform refused has to stay
        // out of the applied sets, and be recorded as refused so the check above can settle.
        writeMirror(desired.withApplied(suspended, hidden));
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

        EnforcementState released = EnforcementState.released();
        clearFactoryResetProtection();
        applyUserRestrictions(previous.userRestrictions, released.userRestrictions);
        Set<String> suspended = applySuspension(previous.suspendedPackages,
                released.suspendedPackages);
        Set<String> hidden = applyHiding(previous.hiddenPackages, released.hiddenPackages);
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
        // A package that refused to come back is still restricted, and saying otherwise would
        // leave the user holding a block that no later reconcile knows to lift.
        writeMirror(released.withApplied(suspended, hidden));
        if (!suspended.isEmpty() || !hidden.isEmpty()) {
            Log.w(TAG, "Release left " + suspended.size() + " suspended and " + hidden.size()
                    + " hidden package(s) that could not be restored");
        }
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

    /**
     * Whether a package the platform refused has turned up since, which is the one thing that
     * makes an otherwise settled state worth applying again.
     *
     * <p>Asked only about the handful of packages that were refused, and a rule normally names
     * an app that is installed, so this is nothing per tick. It is what lets the short reconcile
     * poll still catch an install: a rule written before its app exists is supported, and the
     * app has to be suspended the moment it arrives.</p>
     */
    private boolean anyRefusedPackageIsInstalled(EnforcementState mirror) {
        for (String packageName : mirror.unappliedPackages()) {
            if (isInstalled(packageName)) {
                Log.i(TAG, "Refused package " + packageName + " is installed now; re-applying");
                return true;
            }
        }
        return false;
    }

    private boolean isInstalled(String packageName) {
        try {
            appContext.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
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

    /**
     * One batched call per direction: suspending package by package is a per-package IPC.
     *
     * @return the packages that are suspended once the calls have been made, which is what the
     *         mirror must record. A package the platform refused is not in it, so the next
     *         reconcile sees work left to do instead of a state it thinks it already reached.
     */
    private Set<String> applySuspension(Set<String> previous, Set<String> desired) {
        Set<String> refusedRelease = setSuspended(difference(previous, desired), false);
        Set<String> refusedSuspend = setSuspended(difference(desired, previous), true);
        Set<String> applied = new TreeSet<>(desired);
        applied.removeAll(refusedSuspend);
        // A package that would not come back is still suspended unless it has gone entirely,
        // and one that has gone has no state left to record.
        for (String packageName : refusedRelease) {
            if (isSuspendedNow(packageName)) applied.add(packageName);
        }
        return applied;
    }

    /** @return the packages the platform would not move, empty when every one of them moved. */
    private Set<String> setSuspended(Set<String> packages, boolean suspended) {
        if (packages.isEmpty()) return Collections.emptySet();
        try {
            String[] failed = devicePolicyManager.setPackagesSuspended(
                    admin, packages.toArray(new String[0]), suspended);
            if (failed == null || failed.length == 0) return Collections.emptySet();
            Log.w(TAG, "Suspension refused for " + Arrays.toString(failed));
            return new HashSet<>(Arrays.asList(failed));
        } catch (SecurityException | IllegalArgumentException refused) {
            Log.e(TAG, "Could not change suspension", refused);
            return new HashSet<>(packages);
        }
    }

    /** @return the packages that are hidden once the calls have been made. See
     *          {@link #applySuspension} for why the mirror needs this rather than the desire. */
    private Set<String> applyHiding(Set<String> previous, Set<String> desired) {
        Set<String> refusedReveal = setHidden(difference(previous, desired), false);
        Set<String> refusedHide = setHidden(difference(desired, previous), true);
        Set<String> applied = new TreeSet<>(desired);
        // Both directions are checked against the platform rather than trusted, because
        // setApplicationHidden reports "did not change it" and "could not change it" with the
        // same false, and an already-hidden package is the first of those.
        for (String packageName : refusedHide) {
            if (!isHiddenNow(packageName)) applied.remove(packageName);
        }
        for (String packageName : refusedReveal) {
            if (isHiddenNow(packageName)) applied.add(packageName);
        }
        return applied;
    }

    /** @return the packages the platform would not move, empty when every one of them moved. */
    private Set<String> setHidden(Set<String> packages, boolean hidden) {
        Set<String> failed = new HashSet<>();
        for (String packageName : packages) {
            try {
                if (!devicePolicyManager.setApplicationHidden(admin, packageName, hidden)) {
                    failed.add(packageName);
                }
            } catch (SecurityException | IllegalArgumentException refused) {
                Log.e(TAG, "Could not change hidden state for " + packageName, refused);
                failed.add(packageName);
            }
        }
        if (!failed.isEmpty()) Log.w(TAG, "Hiding refused for " + failed);
        return failed;
    }

    /**
     * Whether the package is suspended right now, read from the platform rather than assumed.
     *
     * <p>Only asked about the handful of packages a call refused, so this is never a per-rule
     * cost. A suspended package still resolves through {@code PackageManager}; one that is not
     * installed does not, and that is the answer we want — nothing there to record.</p>
     */
    private boolean isSuspendedNow(String packageName) {
        try {
            ApplicationInfo info =
                    appContext.getPackageManager().getApplicationInfo(packageName, 0);
            return (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    /** Hiding removes the package from {@code PackageManager}, so the admin has to be asked. */
    private boolean isHiddenNow(String packageName) {
        try {
            return devicePolicyManager.isApplicationHidden(admin, packageName);
        } catch (SecurityException | IllegalArgumentException unknown) {
            return false;
        }
    }

    /**
     * Drops a package the phone no longer has from the mirror.
     *
     * <p>An uninstall changes the device without going through {@link #reconcile}, and the
     * mirror is what reconcile diffs against. Left alone, the entry would make a reinstalled
     * package look like one that is already suspended, so the diff would find nothing to do
     * and the reinstalled copy would run free — uninstall and reinstall being the cheapest
     * bypass there is, since Voward deliberately never blocks either.</p>
     */
    void forgetPackage(String packageName) {
        if (!isProvisioned() || packageName == null || packageName.isEmpty()) return;
        EnforcementState mirror = mirroredState();
        if (!mirror.suspendedPackages.contains(packageName)
                && !mirror.hiddenPackages.contains(packageName)
                && !mirror.unappliedPackages().contains(packageName)) {
            return;
        }
        Log.i(TAG, "Forgetting " + packageName + " from the mirror after an uninstall");
        writeMirror(mirror.withoutPackage(packageName));
    }

    /**
     * Force-stops browsers so that whatever they have on screen has to be fetched again.
     *
     * <p>{@code URLBlocklist} is a navigation check. When a website session ends the rule goes
     * straight back into the policy, but a page that is already loaded never navigates again —
     * an infinite feed will scroll for hours on XHR alone — so the session would end in policy
     * and not on screen.</p>
     *
     * <p>Suspension is not enough: verified on Samsung API 30, suspending the foreground
     * browser drops it to the launcher but leaves the process and the loaded page alive.
     * Hiding is the device owner's only force-stop, and a killed browser has to restore its
     * tab from disk, which is the navigation the policy catches.</p>
     *
     * <p>The hidden state is put back immediately and never reaches the mirror, so this is
     * outside {@link EnforcementState} on purpose. A caller must record the packages first —
     * see {@link #restoreEvictedBrowsers} for what repairs an interrupted eviction.</p>
     */
    void evictBrowsers(Collection<String> browsers) {
        if (!isProvisioned() || browsers == null || browsers.isEmpty()) return;
        Set<String> targets = new HashSet<>(browsers);
        Log.i(TAG, "Evicting " + targets.size() + " browser(s) to end a website session");
        try {
            setHidden(targets, true);
        } finally {
            setHidden(targets, false);
        }
    }

    /** Undoes a hide that an interrupted {@link #evictBrowsers} left behind. */
    void restoreEvictedBrowsers(Collection<String> browsers) {
        if (!isProvisioned() || browsers == null || browsers.isEmpty()) return;
        setHidden(new HashSet<>(browsers), false);
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
