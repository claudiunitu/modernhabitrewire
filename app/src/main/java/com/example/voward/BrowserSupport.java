package com.example.voward;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Central browser compatibility catalog.
 *
 * <p>Browser view IDs are package-scoped, even when several browsers share Chromium or
 * Mozilla Android Components. Keeping the package and all known address-editor IDs together
 * prevents detection and in-place redirect support from drifting apart.</p>
 */
final class BrowserSupport {
    private static final String BLANK_PAGE = "about:blank";
    private static final String GECKO_HOME = "about:home";

    static final class Config {
        final String packageName;
        final String safeAddress;
        final List<String> addressBarIds;

        private Config(String packageName, String safeAddress, List<String> addressBarIds) {
            this.packageName = packageName;
            this.safeAddress = safeAddress;
            this.addressBarIds = Collections.unmodifiableList(addressBarIds);
        }
    }

    private static final List<Config> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            // Google Chrome channels.
            chromium("com.android.chrome"),
            chromium("com.chrome.beta"),
            chromium("com.chrome.dev"),
            chromium("com.chrome.canary"),

            // Chromium-based browsers and their public channels.
            chromium("com.brave.browser"),
            chromium("com.brave.browser_beta"),
            chromium("com.brave.browser_nightly"),
            chromium("com.microsoft.emmx"),
            chromium("com.microsoft.emmx.beta"),
            chromium("com.microsoft.emmx.dev"),
            chromium("com.microsoft.emmx.canary"),
            chromium("com.vivaldi.browser"),
            chromium("com.vivaldi.browser.snapshot"),
            chromium("com.kiwibrowser.browser"),
            chromium("org.cromite.cromite"),
            chromium("com.ecosia.android"),

            // Mozilla Android Components / GeckoView browsers.
            gecko("org.mozilla.firefox"),
            gecko("org.mozilla.firefox_beta"),
            gecko("org.mozilla.fenix"),
            gecko("org.mozilla.focus"),
            gecko("org.mozilla.klar"),
            gecko("org.mozilla.fennec_fdroid"),
            gecko("io.github.forkmaintainers.iceraven"),
            gecko("us.spotco.fennec_dos"),
            gecko("org.torproject.torbrowser"),
            gecko("org.torproject.torbrowser_alpha"),

            // Browsers with their own toolbar resource names.
            custom("com.opera.browser", BLANK_PAGE, "url_field", "url_bar"),
            custom("com.opera.browser.beta", BLANK_PAGE, "url_field", "url_bar"),
            custom("com.opera.mini.native", BLANK_PAGE, "url_field", "url_bar"),
            custom("com.opera.gx", BLANK_PAGE, "url_field", "url_bar"),
            custom("com.duckduckgo.mobile.android", BLANK_PAGE,
                    "omnibarTextInput", "omnibar_text_input", "url_bar"),
            custom("com.sec.android.app.sbrowser", BLANK_PAGE,
                    "location_bar_edit_text", "location_bar", "url_bar"),
            custom("com.sec.android.app.sbrowser.beta", BLANK_PAGE,
                    "location_bar_edit_text", "location_bar", "url_bar")
    ));

    private BrowserSupport() {
    }

    static List<Config> all() {
        return SUPPORTED;
    }

    static Config find(String packageName) {
        if (packageName == null) return null;
        for (Config config : SUPPORTED) {
            if (packageName.equals(config.packageName)) return config;
        }
        return null;
    }

    static boolean isConfiguredSafeAddress(Config config, String visibleAddress) {
        if (config == null || visibleAddress == null) return false;
        return canonicalAddress(config.safeAddress).equals(canonicalAddress(visibleAddress));
    }

    static List<Config> withDiscoveredPackages(Iterable<String> packageNames) {
        return withDiscoveredPackages(packageNames, null);
    }

    static List<Config> withDiscoveredPackages(
            Iterable<String> packageNames, String sharedSafeAddress) {
        List<Config> merged = new ArrayList<>(SUPPORTED);
        Set<String> included = new LinkedHashSet<>();
        for (Config config : SUPPORTED) included.add(config.packageName);
        if (packageNames != null) {
            for (String packageName : packageNames) {
                if (packageName != null && !packageName.isBlank() && included.add(packageName)) {
                    merged.add(generic(packageName));
                }
            }
        }
        if (sharedSafeAddress != null && !sharedSafeAddress.isBlank()) {
            List<Config> redirected = new ArrayList<>(merged.size());
            for (Config config : merged) {
                redirected.add(new Config(
                        config.packageName, sharedSafeAddress,
                        new ArrayList<>(config.addressBarIds)));
            }
            return Collections.unmodifiableList(redirected);
        }
        return Collections.unmodifiableList(merged);
    }

    private static Config chromium(String packageName) {
        return custom(packageName, BLANK_PAGE, "url_bar", "url_edit_text", "location_bar");
    }

    private static Config gecko(String packageName) {
        return custom(packageName, GECKO_HOME,
                "mozac_browser_toolbar_url_view",
                "mozac_browser_toolbar_edit_url_view",
                "mozac_browser_toolbar_url_edit",
                "url_bar_title");
    }

    private static Config generic(String packageName) {
        // about:blank is understood by both Chromium and Gecko. The broad ID set is only
        // applied to packages Android itself reports as browsers.
        return custom(packageName, BLANK_PAGE,
                "url_bar", "url_edit_text", "location_bar", "location_bar_edit_text",
                "url_field", "omnibarTextInput", "omnibar_text_input",
                "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view",
                "mozac_browser_toolbar_url_edit", "url_bar_title");
    }

    private static Config custom(String packageName, String safeAddress, String... localIds) {
        Set<String> fullIds = new LinkedHashSet<>();
        for (String localId : localIds) {
            fullIds.add(packageName + ":id/" + localId);
        }
        return new Config(packageName, safeAddress, new ArrayList<>(fullIds));
    }

    private static String canonicalAddress(String address) {
        String canonical = address.trim().toLowerCase(Locale.ROOT);
        if (canonical.startsWith("http://")) canonical = canonical.substring(7);
        else if (canonical.startsWith("https://")) canonical = canonical.substring(8);
        while (canonical.endsWith("/")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        return canonical;
    }
}
