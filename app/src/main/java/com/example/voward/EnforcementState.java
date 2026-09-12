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
    private static final String KEY_UNAPPLIED_SUSPENDED = "unappliedSuspended";
    private static final String KEY_UNAPPLIED_HIDDEN = "unappliedHidden";

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
    /** Mirror only: wanted suspended, refused by the platform. See {@link #withApplied}. */
    final Set<String> unappliedSuspended;
    /** Mirror only: wanted hidden, refused by the platform. */
    final Set<String> unappliedHidden;

    private EnforcementState(boolean active, Collection<String> suspendedPackages,
                             Collection<String> hiddenPackages,
                             Collection<String> userRestrictions, boolean uninstallBlocked,
                             Collection<String> urlBlocklist,
                             Collection<String> managedBrowsers,
                             Collection<String> unappliedSuspended,
                             Collection<String> unappliedHidden) {
        this.active = active;
        this.suspendedPackages = sortedCopy(suspendedPackages);
        this.hiddenPackages = sortedCopy(hiddenPackages);
        this.userRestrictions = sortedCopy(userRestrictions);
        this.uninstallBlocked = uninstallBlocked;
        this.urlBlocklist = sortedCopy(urlBlocklist);
        this.managedBrowsers = sortedCopy(managedBrowsers);
        this.unappliedSuspended = sortedCopy(unappliedSuspended);
        this.unappliedHidden = sortedCopy(unappliedHidden);
    }

    /** Nothing enforced. Both the pre-activation state and the post-release state. */
    static EnforcementState released() {
        return new EnforcementState(false, null, null, null, false, null, null, null, null);
    }

    static EnforcementState active(Collection<String> suspendedPackages,
                                   Collection<String> hiddenPackages,
                                   Collection<String> userRestrictions,
                                   boolean uninstallBlocked,
                                   Collection<String> urlBlocklist,
                                   Collection<String> managedBrowsers) {
        return new EnforcementState(true, suspendedPackages, hiddenPackages, userRestrictions,
                uninstallBlocked, urlBlocklist, managedBrowsers, null, null);
    }

    /**
     * This state with the packages that were really suspended and hidden, for the mirror.
     *
     * <p>The mirror has to describe the device rather than the intention. {@code
     * setPackagesSuspended} refuses a package that is not installed, and writing a rule before
     * the app it names is something Voward supports on purpose — so recording the refusal as
     * success would leave {@link PolicyReconciler#reconcile} short-circuiting for ever on a
     * state the device never reached, and the app would go unsuspended once installed.</p>
     *
     * <p>What was refused is recorded alongside, which is what lets the comparison in
     * {@link #matchesApplied} settle instead of finding work to do on every single tick.</p>
     */
    EnforcementState withApplied(Collection<String> appliedSuspended,
                                 Collection<String> appliedHidden) {
        Set<String> suspendedNow = sortedCopy(appliedSuspended);
        Set<String> hiddenNow = sortedCopy(appliedHidden);
        return new EnforcementState(active, suspendedNow, hiddenNow, userRestrictions,
                uninstallBlocked, urlBlocklist, managedBrowsers,
                without(suspendedPackages, suspendedNow), without(hiddenPackages, hiddenNow));
    }

    /**
     * This mirror with one package dropped from every record of it, after an uninstall.
     *
     * <p>{@link #withApplied} cannot be used for that: it works out what was refused by
     * subtracting the applied sets from the desired ones, and a mirror is not a desire. A
     * package that is gone was not refused, it is simply gone.</p>
     */
    EnforcementState withoutPackage(String packageName) {
        return new EnforcementState(active, minus(suspendedPackages, packageName),
                minus(hiddenPackages, packageName), userRestrictions, uninstallBlocked,
                urlBlocklist, managedBrowsers, minus(unappliedSuspended, packageName),
                minus(unappliedHidden, packageName));
    }

    /**
     * Whether {@code applied} is this state as far as the device would let it be taken.
     *
     * <p>Plain equality cannot answer that. {@code setPackagesSuspended} refuses a package that
     * is not installed, and writing a rule ahead of the app it names is supported on purpose, so
     * the mirror stays short of the desire by exactly that package — and a reconcile that
     * short-circuits on equality then never short-circuits again. Measured on a Pixel 10, one
     * rule naming an app that was not installed had every user restriction, suspension, hide,
     * uninstall block and browser blocklist rewritten every two minutes, indefinitely.</p>
     *
     * <p>Recording what the platform refused keeps "we asked and were told no" apart from "we
     * never asked". The caller still has to notice a refused package that has since appeared;
     * this only says that nothing else has moved.</p>
     */
    boolean matchesApplied(EnforcementState applied) {
        return active == applied.active
                && uninstallBlocked == applied.uninstallBlocked
                && userRestrictions.equals(applied.userRestrictions)
                && urlBlocklist.equals(applied.urlBlocklist)
                && managedBrowsers.equals(applied.managedBrowsers)
                && suspendedPackages.equals(
                        union(applied.suspendedPackages, applied.unappliedSuspended))
                && hiddenPackages.equals(union(applied.hiddenPackages, applied.unappliedHidden));
    }

    /** The packages the platform would not move, which a later reconcile has to re-try. */
    Set<String> unappliedPackages() {
        return union(unappliedSuspended, unappliedHidden);
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
     * <p>The 4.7 guard rail used to skip the sweep unless a filtering browser survived the app
     * rules, on the grounds that a phone with no browser is worse than a phone with an
     * unfiltered one. It is gone: that reasoning also let the user uninstall every filtering
     * browser and keep the unfilterable one, which turns every website rule off at once. The
     * sweep now runs whatever is left, so the answer to "nothing here can be filtered" is no
     * browsing rather than free browsing.</p>
     */
    private static void suspendUnfilterableBrowsers(BrowserPolicy.Landscape browsers,
                                                    String ownPackage,
                                                    Set<String> criticalPackages,
                                                    Set<String> suspended, Set<String> hidden) {
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
        bundle.putStringArray(KEY_UNAPPLIED_SUSPENDED, toArray(unappliedSuspended));
        bundle.putStringArray(KEY_UNAPPLIED_HIDDEN, toArray(unappliedHidden));
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
                    toList(bundle.getStringArray(KEY_MANAGED_BROWSERS)),
                    toList(bundle.getStringArray(KEY_UNAPPLIED_SUSPENDED)),
                    toList(bundle.getStringArray(KEY_UNAPPLIED_HIDDEN)));
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
                && managedBrowsers.equals(that.managedBrowsers)
                && unappliedSuspended.equals(that.unappliedSuspended)
                && unappliedHidden.equals(that.unappliedHidden);
    }

    @Override
    public int hashCode() {
        return Objects.hash(active, uninstallBlocked, suspendedPackages, hiddenPackages,
                userRestrictions, urlBlocklist, managedBrowsers, unappliedSuspended,
                unappliedHidden);
    }

    @Override
    public String toString() {
        return "EnforcementState{active=" + active
                + ", suspended=" + suspendedPackages.size()
                + ", hidden=" + hiddenPackages.size()
                + ", restrictions=" + userRestrictions
                + ", uninstallBlocked=" + uninstallBlocked
                + ", urlRules=" + urlBlocklist.size()
                + ", browsers=" + managedBrowsers.size()
                + ", unapplied=" + (unappliedSuspended.size() + unappliedHidden.size()) + "}";
    }

    private static Set<String> sortedCopy(Collection<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptySet();
        Set<String> copy = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) copy.add(value.trim());
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> without(Set<String> values, Set<String> removed) {
        if (values.isEmpty()) return Collections.emptySet();
        Set<String> remaining = new TreeSet<>(values);
        remaining.removeAll(removed);
        return Collections.unmodifiableSet(remaining);
    }

    private static Set<String> minus(Set<String> values, String removed) {
        if (!values.contains(removed)) return values;
        Set<String> remaining = new TreeSet<>(values);
        remaining.remove(removed);
        return Collections.unmodifiableSet(remaining);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        if (second.isEmpty()) return first;
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return Collections.unmodifiableSet(all);
    }

    private static String[] toArray(Set<String> values) {
        return values.toArray(new String[0]);
    }

    private static Collection<String> toList(String[] values) {
        return values == null ? null : Arrays.asList(values);
    }
}
