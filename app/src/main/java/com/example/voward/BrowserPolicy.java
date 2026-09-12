package com.example.voward;

import android.content.Context;
import android.content.Intent;
import android.content.RestrictionEntry;
import android.content.RestrictionsManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Website rules, expressed as Chromium enterprise policy instead of as an accessibility service
 * reading the address bar.
 *
 * <p>{@code URLBlocklist} is enforced inside the browser's own network stack, so it holds in
 * fullscreen, across redirects, in subframes and in Custom Tabs — none of which the address-bar
 * approach ever covered. The policy lives in system_server, so clearing the browser's data does
 * not remove it.</p>
 *
 * <p>A browser Voward cannot configure is suspended rather than filtered, and a browser that
 * declares nothing is <em>unknown</em>, not unsupported: the manifest declaration exists so MDM
 * consoles can list keys, and a Chromium fork can honour a policy it never advertises. That is
 * what the "test this browser" verdict is for.</p>
 */
final class BrowserPolicy {

    private static final String TAG = "BrowserPolicy";

    /** Chromium's key. Declared {@code restrictionType="string"}, so the value is JSON text. */
    static final String KEY_URL_BLOCKLIST = "URLBlocklist";

    /** The domain the "test this browser" flow asks the user to open. */
    static final String PROBE_DOMAIN = "voward-block-test.invalid";

    /** Long enough to keep a burst of reconciles cheap, short enough to notice an install. */
    private static final long SURVEY_TTL_MS = 60_000L;

    private static volatile Landscape cachedLandscape;
    private static volatile long cachedAtElapsedMs = -1;

    private BrowserPolicy() {}

    /**
     * Drops the cached survey. Called when a package changes and when the user records a
     * verdict, both of which change the answer immediately rather than in a minute.
     */
    static void invalidate() {
        cachedLandscape = null;
        cachedAtElapsedMs = -1;
    }

    /** How each installed browser is treated, once the verdicts and the probe are applied. */
    static final class Landscape {
        /** Browsers that get {@code URLBlocklist} pushed to them. */
        final Set<String> filterable;
        /** Browsers that cannot be filtered and are suspended instead. */
        final Set<String> unfilterable;

        private Landscape(Set<String> filterable, Set<String> unfilterable) {
            this.filterable = Collections.unmodifiableSet(filterable);
            this.unfilterable = Collections.unmodifiableSet(unfilterable);
        }

        static Landscape empty() {
            return new Landscape(new TreeSet<>(), new TreeSet<>());
        }
    }

    /**
     * Sorts every installed browser into filterable and unfilterable.
     *
     * <p>An empty filterable set is a real answer and is reported as one. Section 4.7 used to
     * collapse the whole landscape in that case, so that a phone with only Firefox on it kept a
     * working browser; the cost was that uninstalling every filtering browser turned every
     * website rule off, which is the cheapest bypass in the app. Suspending the last browser
     * leaves a phone that cannot open a link until a filtering one is installed, and that is
     * the trade that was chosen.</p>
     */
    static Landscape survey(Context context, AppPreferencesManagerSingleton preferences) {
        long now = SystemClock.elapsedRealtime();
        Landscape cached = cachedLandscape;
        if (cached != null && now - cachedAtElapsedMs < SURVEY_TTL_MS
                && now >= cachedAtElapsedMs) {
            return cached;
        }
        Landscape surveyed = surveyNow(context, preferences);
        cachedLandscape = surveyed;
        cachedAtElapsedMs = now;
        return surveyed;
    }

    private static Landscape surveyNow(Context context,
                                       AppPreferencesManagerSingleton preferences) {
        forgetStaleVerdicts(context, preferences);
        Set<String> filterable = new TreeSet<>();
        Set<String> unfilterable = new TreeSet<>();
        Set<String> rejected = preferences.getRejectedBrowserPackages();
        Set<String> approved = preferences.getApprovedBrowserPackages();

        for (String packageName : installedBrowsers(context)) {
            if (rejected.contains(packageName)) unfilterable.add(packageName);
            else if (approved.contains(packageName)) filterable.add(packageName);
            else if (declaresUrlBlocklist(context, packageName)) filterable.add(packageName);
            else unfilterable.add(packageName);
        }
        return new Landscape(filterable, unfilterable);
    }

    /**
     * Everything that answers a browsable {@code http} intent.
     *
     * <p>Resolved rather than listed. The hard-coded package list this replaces could not see a
     * browser released after it was written, which is the whole population that matters.</p>
     */
    static Set<String> installedBrowsers(Context context) {
        Set<String> packages = new TreeSet<>();
        PackageManager packageManager = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo info : packageManager.queryIntentActivities(probe,
                PackageManager.MATCH_ALL)) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String packageName = info.activityInfo.packageName;
            if (!packageName.equals(context.getPackageName())) packages.add(packageName);
        }
        return packages;
    }

    /**
     * Section 4.4: a browser can stop honouring policy after an update, so a verdict the user
     * gave about an older build is not evidence about this one.
     *
     * <p>Compares install times rather than listening for {@code PACKAGE_REPLACED}: that
     * receiver lived in the accessibility service, and a broadcast can be missed while this
     * cannot. Only a verdict is forgotten, and only for a browser that does not declare the
     * key — one that advertises support is re-judged from its own manifest every survey.</p>
     */
    private static void forgetStaleVerdicts(Context context,
                                            AppPreferencesManagerSingleton preferences) {
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : preferences.getApprovedBrowserPackages()) {
            long verdictAt = preferences.getBrowserVerdictTime(packageName);
            if (verdictAt <= 0) continue;
            long updatedAt;
            try {
                updatedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException uninstalled) {
                continue;
            }
            if (updatedAt <= verdictAt) continue;
            if (declaresUrlBlocklist(context, packageName)) continue;
            Log.i(TAG, "Forgetting the browser verdict for " + packageName + " after an update");
            preferences.clearBrowserVerdict(packageName);
        }
    }

    /**
     * The browser a website session is attributed to: the phone default when it is one of the
     * filtered browsers, otherwise any of them.
     *
     * <p>A website session has to be metered against something, and the meter watches a
     * package. Guessing wrong only means the allowance is charged for time in a different
     * filtered browser, which is still time spent on the rule that was lifted.</p>
     */
    static String sessionBrowser(Context context, Set<String> managedBrowsers) {
        if (managedBrowsers == null || managedBrowsers.isEmpty()) return null;
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo preferred = context.getPackageManager().resolveActivity(probe,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred != null && preferred.activityInfo != null
                && managedBrowsers.contains(preferred.activityInfo.packageName)) {
            return preferred.activityInfo.packageName;
        }
        return managedBrowsers.iterator().next();
    }

    /** Whether the package advertises {@code URLBlocklist} in its managed-config manifest. */
    static boolean declaresUrlBlocklist(Context context, String packageName) {
        RestrictionsManager restrictions = (RestrictionsManager)
                context.getSystemService(Context.RESTRICTIONS_SERVICE);
        if (restrictions == null) return false;
        List<RestrictionEntry> declared;
        try {
            declared = restrictions.getManifestRestrictions(packageName);
        } catch (RuntimeException unreadable) {
            Log.d(TAG, "Could not read manifest restrictions for " + packageName, unreadable);
            return false;
        }
        if (declared == null) return false;
        for (RestrictionEntry entry : declared) {
            if (KEY_URL_BLOCKLIST.equals(entry.getKey())) return true;
        }
        return false;
    }

    /**
     * Turns Voward's rules into {@code URLBlocklist} entries.
     *
     * <p>The grammars overlap almost exactly — {@code [scheme://][.]host[:port][/path][@query]}
     * covers every rule shape the app accepts — with one exception. {@code keyword:} rules match
     * page text, and {@code URLBlocklist} matches URLs, so they have no translation at all and
     * are dropped here. {@link #keywordRules} names them so the user can be told.</p>
     */
    static List<String> translate(Collection<String> rules) {
        List<String> entries = new ArrayList<>();
        if (rules == null) return entries;
        for (String rule : rules) {
            if (rule == null) continue;
            String pattern = rule.trim();
            if (pattern.isEmpty()) continue;
            if (isKeywordRule(pattern)) continue;
            if (!entries.contains(pattern)) entries.add(pattern);
        }
        return entries;
    }

    /** The rules that cannot survive the move, in the order the user wrote them. */
    static List<String> keywordRules(Collection<String> rules) {
        List<String> retired = new ArrayList<>();
        if (rules == null) return retired;
        for (String rule : rules) {
            if (rule != null && isKeywordRule(rule.trim()) && !retired.contains(rule.trim())) {
                retired.add(rule.trim());
            }
        }
        return retired;
    }

    static boolean isKeywordRule(String rule) {
        return rule != null && rule.trim().toLowerCase(Locale.ROOT).startsWith("keyword:");
    }

    /** Chromium reads this key as one JSON array in a single string, not as a string array. */
    static String toPolicyValue(Collection<String> entries) {
        return new JSONArray(entries == null ? Collections.emptyList() : entries).toString();
    }
}
