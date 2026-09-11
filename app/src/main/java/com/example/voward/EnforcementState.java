package com.example.voward;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The complete description of what the device should look like right now.
 *
 * <p>One object describes the desired state and {@link PolicyReconciler} is the only thing
 * allowed to act on it. Any policy applied from somewhere else would be a restriction that
 * release does not know how to clear, which is how a self-management tool becomes a trap.</p>
 *
 * <p>Website rules are part of this state too: {@code urlBlocklist} is the Chromium policy
 * pushed to every browser in {@code managedBrowsers}, and a browser that cannot take the
 * policy is in {@code suspendedPackages} instead.</p>
 */
final class EnforcementState {

    private static final String KEY_ACTIVE = "active";
    private static final String KEY_SUSPENDED = "suspendedPackages";
    private static final String KEY_HIDDEN = "hiddenPackages";
    private static final String KEY_RESTRICTIONS = "userRestrictions";
    private static final String KEY_UNINSTALL_BLOCKED = "uninstallBlocked";
    private static final String KEY_URL_BLOCKLIST = "urlBlocklist";
    private static final String KEY_MANAGED_BROWSERS = "managedBrowsers";

    final boolean active;
    /** Regular rules: the icon stays and the system explains the block. */
    final Set<String> suspendedPackages;
    /** Strict rules: the package is gone from the launcher entirely. */
    final Set<String> hiddenPackages;
    /** {@code android.os.UserManager} DISALLOW_* keys. */
    final Set<String> userRestrictions;
    final boolean uninstallBlocked;
    /** Website rules, in Chromium {@code URLBlocklist} form. */
    final Set<String> urlBlocklist;
    /** The browsers that receive {@code urlBlocklist}. */
    final Set<String> managedBrowsers;

    private EnforcementState(boolean active, Collection<String> suspendedPackages,
                             Collection<String> hiddenPackages,
                             Collection<String> userRestrictions, boolean uninstallBlocked,
                             Collection<String> urlBlocklist,
                             Collection<String> managedBrowsers) {
        this.active = active;
        this.suspendedPackages = sortedCopy(suspendedPackages);
        this.hiddenPackages = sortedCopy(hiddenPackages);
        this.userRestrictions = sortedCopy(userRestrictions);
        this.uninstallBlocked = uninstallBlocked;
        this.urlBlocklist = sortedCopy(urlBlocklist);
        this.managedBrowsers = sortedCopy(managedBrowsers);
    }

    /** Nothing enforced. Both the pre-activation state and the post-release state. */
    static EnforcementState released() {
        return new EnforcementState(false, null, null, null, false, null, null);
    }

    static EnforcementState active(Collection<String> suspendedPackages,
                                   Collection<String> hiddenPackages,
                                   Collection<String> userRestrictions,
                                   boolean uninstallBlocked,
                                   Collection<String> urlBlocklist,
                                   Collection<String> managedBrowsers) {
        return new EnforcementState(true, suspendedPackages, hiddenPackages, userRestrictions,
                uninstallBlocked, urlBlocklist, managedBrowsers);
    }

    /**
     * What protection currently asks for.
     *
     * <p>Strict rules are hidden, regular rules are suspended, and the single package holding
     * an approved session is left alone. Anything {@code criticalPackages} names is dropped
     * before it reaches the device: rules are already screened when they are added, but
     * suspending the dialer is not a mistake worth risking twice.</p>
     *
     * <p>The tamper restrictions come from {@link TamperPolicy}, which is the only place that
     * names a {@code DISALLOW_*} key.</p>
     */
    static EnforcementState desiredFor(AppPreferencesManagerSingleton preferences,
                                       String ownPackage, Set<String> criticalPackages,
                                       BrowserPolicy.Landscape browsers) {
        if (!preferences.getIsBlockerActive()) return released();

        Set<String> hidden = new TreeSet<>();
        Set<String> suspended = new TreeSet<>();
        String sessionPackage = preferences.getManagedSessionPackage();
        List<String> strict = preferences.getStrictRestrictedAppPackages();

        for (String rule : preferences.getRestrictedAppPackages()) {
            if (rule == null || rule.trim().isEmpty()) continue;
            String packageName = rule.trim();
            if (SafetyPolicy.isCriticalPackage(packageName, ownPackage, criticalPackages)) continue;
            if (strict.contains(packageName)) {
                hidden.add(packageName);
            } else if (!packageName.equals(sessionPackage)) {
                suspended.add(packageName);
            }
        }
        // A quarantined app is paused with no rule of its own: the pause is the question, and
        // answering it either writes a rule or lets the app go.
        for (String packageName : preferences.getQuarantinedPackages()) {
            if (SafetyPolicy.isCriticalPackage(packageName, ownPackage, criticalPackages)) continue;
            if (!packageName.equals(sessionPackage)) suspended.add(packageName);
        }

        BrowserPolicy.Landscape landscape =
                browsers == null ? BrowserPolicy.Landscape.empty() : browsers;
        List<String> blocklist = BrowserPolicy.translate(openWebsiteRules(preferences));
        // Nothing to escape to means nothing to close off: with no website rules, pausing a
        // browser Voward cannot filter would be a restriction the user never asked for.
        if (!preferences.getRestrictedUrls().isEmpty()) {
            suspendUnfilterableBrowsers(landscape, ownPackage, criticalPackages, suspended, hidden);
        }

        return active(suspended, hidden, TamperPolicy.restrictionsFor(preferences),
                preferences.isUninstallGuardEnabled(), blocklist, landscape.filterable);
    }

    /**
     * The website rules to push, with the one under an approved session left out.
     *
     * <p>Dropping a rule from the list for the length of a session is the URL analogue of
     * unsuspending a package. A strict rule is never dropped: strict means there is no session
     * to be had.</p>
     */
    private static List<String> openWebsiteRules(AppPreferencesManagerSingleton preferences) {
        String sessionPattern = preferences.getManagedSessionUrlPattern().trim();
        List<String> rules = new ArrayList<>();
        for (String rule : preferences.getRestrictedUrls()) {
            if (rule == null || rule.trim().isEmpty()) continue;
            String pattern = rule.trim();
            if (!sessionPattern.isEmpty() && pattern.equalsIgnoreCase(sessionPattern)
                    && !preferences.isStrictRestrictedUrlPattern(pattern)) {
                continue;
            }
            rules.add(pattern);
        }
        return rules;
    }

    /**
     * Section 4.6: a browser Voward cannot configure is suspended, not hidden. The icon staying
     * put with a system explanation reads as a decision; the icon vanishing reads as a bug.
     *
     * <p>Section 4.7 guard rail: the sweep is skipped entirely unless at least one filtering
     * browser survives the app rules the user wrote. Leaving the phone with no way to open a
     * link is a worse outcome than leaving one unfiltered browser on it.</p>
     */
    private static void suspendUnfilterableBrowsers(BrowserPolicy.Landscape browsers,
                                                    String ownPackage,
                                                    Set<String> criticalPackages,
                                                    Set<String> suspended, Set<String> hidden) {
        Set<String> stillUsable = new TreeSet<>(browsers.filterable);
        stillUsable.removeAll(suspended);
        stillUsable.removeAll(hidden);
        if (stillUsable.isEmpty()) return;

        for (String browser : browsers.unfilterable) {
            if (SafetyPolicy.isCriticalPackage(browser, ownPackage, criticalPackages)) continue;
            if (hidden.contains(browser)) continue;
            suspended.add(browser);
        }
    }

    /**
     * The form written to {@code setApplicationRestrictions(admin, self, ...)}, which lives in
     * system_server and therefore outlives the app's data directory.
     */
    Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_ACTIVE, active);
        bundle.putStringArray(KEY_SUSPENDED, toArray(suspendedPackages));
        bundle.putStringArray(KEY_HIDDEN, toArray(hiddenPackages));
        bundle.putStringArray(KEY_RESTRICTIONS, toArray(userRestrictions));
        bundle.putBoolean(KEY_UNINSTALL_BLOCKED, uninstallBlocked);
        bundle.putStringArray(KEY_URL_BLOCKLIST, toArray(urlBlocklist));
        bundle.putStringArray(KEY_MANAGED_BROWSERS, toArray(managedBrowsers));
        return bundle;
    }

    /** Returns {@link #released()} for a missing or unreadable bundle: release, never enforce. */
    static EnforcementState fromBundle(Bundle bundle) {
        if (bundle == null || !bundle.containsKey(KEY_ACTIVE)) return released();
        try {
            return new EnforcementState(
                    bundle.getBoolean(KEY_ACTIVE, false),
                    toList(bundle.getStringArray(KEY_SUSPENDED)),
                    toList(bundle.getStringArray(KEY_HIDDEN)),
                    toList(bundle.getStringArray(KEY_RESTRICTIONS)),
                    bundle.getBoolean(KEY_UNINSTALL_BLOCKED, false),
                    toList(bundle.getStringArray(KEY_URL_BLOCKLIST)),
                    toList(bundle.getStringArray(KEY_MANAGED_BROWSERS)));
        } catch (RuntimeException unreadable) {
            return released();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EnforcementState)) return false;
        EnforcementState that = (EnforcementState) other;
        return active == that.active
                && uninstallBlocked == that.uninstallBlocked
                && suspendedPackages.equals(that.suspendedPackages)
                && hiddenPackages.equals(that.hiddenPackages)
                && userRestrictions.equals(that.userRestrictions)
                && urlBlocklist.equals(that.urlBlocklist)
                && managedBrowsers.equals(that.managedBrowsers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(active, uninstallBlocked, suspendedPackages, hiddenPackages,
                userRestrictions, urlBlocklist, managedBrowsers);
    }

    @Override
    public String toString() {
        return "EnforcementState{active=" + active
                + ", suspended=" + suspendedPackages.size()
                + ", hidden=" + hiddenPackages.size()
                + ", restrictions=" + userRestrictions
                + ", uninstallBlocked=" + uninstallBlocked
                + ", urlRules=" + urlBlocklist.size()
                + ", browsers=" + managedBrowsers.size() + "}";
    }

    private static Set<String> sortedCopy(Collection<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptySet();
        Set<String> copy = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) copy.add(value.trim());
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String[] toArray(Set<String> values) {
        return values.toArray(new String[0]);
    }

    private static Collection<String> toList(String[] values) {
        return values == null ? null : Arrays.asList(values);
    }
}
