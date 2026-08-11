package com.example.voward;

import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExampleUnitTest {
    @Test
    public void dailyAllowancesHaveBoundedCarryAndNeverAllowDebt() {
        assertEquals(1800, BudgetMath.addDailyAllowancesBounded(1000, 1800, 1, 1));
        assertEquals(1800, BudgetMath.addDailyAllowancesBounded(-500, 1800, 1, 1));
        assertEquals(1800, BudgetMath.addDailyAllowancesBounded(-500, 1800, 2, 1));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(500, 0, 1, 1));
        assertEquals(0, BudgetMath.addDailyAllowancesBounded(-900, 1800, 0, 1));
        assertEquals(0, BudgetMath.subtractCost(10, 11));
        assertEquals(0, BudgetMath.subtractCost(-10, 1));
        assertEquals(5, BudgetMath.subtractCost(10, 5));
    }

    @Test
    public void waitTimeIsBoundedAndFinite() {
        assertEquals(30, BudgetMath.calculateReentryWaitSeconds(30, .35, 0));
        assertEquals(37, BudgetMath.calculateReentryWaitSeconds(30, .35, 1));
        assertEquals(3600, BudgetMath.calculateReentryWaitSeconds(3600, 1, Integer.MAX_VALUE));
        assertEquals(300, BudgetMath.quoteSessionSeconds(1000, 300));
        assertEquals(100, BudgetMath.quoteSessionSeconds(100, 300));
        assertEquals(5, BudgetMath.elapsedCostSeconds(5999));
    }

    @Test
    public void urlMatchingRespectsHostnameBoundariesAndPaths() {
        assertTrue(UrlPatternMatcher.matches("https://m.example.com/news/today", "example.com/news"));
        assertTrue(UrlPatternMatcher.matches("example.com", "www.example.com"));
        assertFalse(UrlPatternMatcher.matches("https://notexample.com", "example.com"));
        assertFalse(UrlPatternMatcher.matches("https://safe.test/?next=example.com", "example.com"));
        assertFalse(UrlPatternMatcher.matches("https://example.com/sports", "example.com/news"));
        assertFalse(UrlPatternMatcher.matches("https://example.com/newspaper", "example.com/news"));
        assertTrue(UrlPatternMatcher.matches("https://example.com/news/world", "example.com/news"));
        assertTrue(UrlPatternMatcher.matches("https://example.com/search?q=focus", "example.com/search?q=focus"));
        assertFalse(UrlPatternMatcher.matches("https://example.com/search?q=noise", "example.com/search?q=focus"));
        assertTrue(UrlPatternMatcher.matches("https://safe.test/shorts", "keyword:shorts"));
        assertFalse(UrlPatternMatcher.matches("https://safe.test/shorts", "shorts"));
        assertTrue(UrlPatternMatcher.isValidPattern("example.com/news"));
        assertTrue(UrlPatternMatcher.isValidPattern("keyword:shorts"));
        assertFalse(UrlPatternMatcher.isValidPattern("shorts"));
    }

    @Test
    public void criticalAppsCannotBeRestricted() {
        assertTrue(SafetyPolicy.isCriticalPackage("com.android.dialer", "my.app"));
        assertTrue(SafetyPolicy.isCriticalPackage("my.app", "my.app"));
        assertTrue(SafetyPolicy.isCriticalPackage("org.vendor.emergency", "my.app"));
        assertFalse(SafetyPolicy.isCriticalPackage("com.example.social", "my.app"));
    }

    @Test
    public void wholeBrowserBlocksAreMeteredButUrlOnlySessionsCanPause() {
        assertTrue(InterceptionPolicy.shouldStartSessionTimer("APP", true, false));
        assertTrue(InterceptionPolicy.shouldStartSessionTimer("URL", true, true));
        assertFalse(InterceptionPolicy.shouldStartSessionTimer("URL", true, false));
        assertTrue(InterceptionPolicy.isApprovedWholeBrowserSession("APP", true, true));
        assertFalse(InterceptionPolicy.isApprovedWholeBrowserSession("URL", true, true));
    }

    @Test
    public void gateCountdownUsesAbsoluteDeadlineAcrossRecreation() {
        assertEquals(30, DecisionGatePolicy.remainingSeconds(40_000, 10_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(40_000, 39_001));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(40_000, 40_000));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(40_000, 50_000));
    }

    @Test
    public void autocompleteIsNeverTreatedAsCommittedNavigation() {
        List<String> restricted = List.of("www.youtube.com");

        assertEquals(null, BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "www.youtube.com", "www.youtube.com", true, restricted));
        assertEquals(null, BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "www.y", "www.y", true, restricted));
    }

    @Test
    public void directAndRestoredRestrictedUrlsAreCommitted() {
        List<String> restricted = List.of("www.youtube.com");

        assertEquals("www.youtube.com", BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "https://www.youtube.com/watch?v=abc",
                "https://www.youtube.com/watch?v=abc", false, restricted));
        assertEquals("www.youtube.com", BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "www.youtube.com", "www.youtube.com", false, restricted));
    }

    @Test
    public void staleDeferredUrlCannotBlockCurrentTab() {
        List<String> restricted = List.of("www.youtube.com");

        assertEquals(null, BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "www.youtube.com", "www.example.com", false, restricted));
    }

    @Test
    public void strictUrlRulesTakePriorityOverBroaderBudgetRules() {
        BrowserUrlEnforcementPolicy.RuleMatch match =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                        "https://example.com/videos/123",
                        "https://example.com/videos/123",
                        false,
                        List.of("example.com/videos"),
                        List.of("example.com"));

        assertNotNull(match);
        assertEquals("example.com/videos", match.pattern);
        assertTrue(match.strict);

        BrowserUrlEnforcementPolicy.RuleMatch regular =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                        "https://example.com/news",
                        "https://example.com/news",
                        false,
                        List.of("example.com/videos"),
                        List.of("example.com"));
        assertNotNull(regular);
        assertEquals("example.com", regular.pattern);
        assertFalse(regular.strict);
    }

    @Test
    public void expandedBrowserCatalogHasUniquePackagesAndSafeRedirects() {
        assertTrue(BrowserSupport.all().size() >= 30);
        assertEquals(BrowserSupport.all().size(), BrowserSupport.all().stream()
                .map(config -> config.packageName)
                .distinct()
                .count());

        BrowserSupport.Config edge = BrowserSupport.find("com.microsoft.emmx");
        BrowserSupport.Config samsung = BrowserSupport.find("com.sec.android.app.sbrowser");
        BrowserSupport.Config firefoxFocus = BrowserSupport.find("org.mozilla.focus");
        BrowserSupport.Config tor = BrowserSupport.find("org.torproject.torbrowser");

        assertNotNull(edge);
        assertNotNull(samsung);
        assertNotNull(firefoxFocus);
        assertNotNull(tor);
        assertEquals("about:blank", edge.safeAddress);
        assertEquals("about:blank", samsung.safeAddress);
        assertEquals("about:home", firefoxFocus.safeAddress);
        assertEquals("about:home", tor.safeAddress);
        assertTrue(edge.addressBarIds.contains("com.microsoft.emmx:id/url_bar"));
        assertTrue(samsung.addressBarIds.contains(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"));
        assertNull(BrowserSupport.find("com.example.not.a.browser"));
    }

    @Test
    public void systemDiscoveredBrowsersReceiveGenericFallbackWithoutDuplicates() {
        List<BrowserSupport.Config> merged = BrowserSupport.withDiscoveredPackages(Arrays.asList(
                "com.android.chrome", "org.example.newbrowser", "org.example.newbrowser"));

        assertEquals(BrowserSupport.all().size() + 1, merged.size());
        BrowserSupport.Config discovered = merged.stream()
                .filter(config -> config.packageName.equals("org.example.newbrowser"))
                .findFirst()
                .orElse(null);
        assertNotNull(discovered);
        assertEquals("about:blank", discovered.safeAddress);
        assertTrue(discovered.addressBarIds.contains("org.example.newbrowser:id/url_bar"));
        assertTrue(discovered.addressBarIds.contains(
                "org.example.newbrowser:id/mozac_browser_toolbar_url_view"));
    }

    @Test
    public void localBlockPageAddressOverridesEveryBrowserFallback() {
        String blockPage = "http://127.0.0.1:54321/blocked";
        List<BrowserSupport.Config> redirected = BrowserSupport.withDiscoveredPackages(
                List.of("org.example.newbrowser"), blockPage);

        assertEquals(BrowserSupport.all().size() + 1, redirected.size());
        assertTrue(redirected.stream().allMatch(config -> blockPage.equals(config.safeAddress)));
        BrowserSupport.Config chrome = redirected.stream()
                .filter(config -> "com.android.chrome".equals(config.packageName))
                .findFirst().orElse(null);
        assertTrue(BrowserSupport.isConfiguredSafeAddress(
                chrome, "127.0.0.1:54321/blocked/"));
        assertFalse(BrowserSupport.isConfiguredSafeAddress(
                chrome, "127.0.0.1:54321/not-blocked"));
        assertEquals("about:blank", BrowserSupport.find("com.android.chrome").safeAddress);
        assertEquals("about:home", BrowserSupport.find("org.mozilla.firefox").safeAddress);
    }

    @Test
    public void localBlockPageIsServedWithoutExternalNetwork() throws Exception {
        StaticBlockPageServer server = new StaticBlockPageServer();
        try {
            String address = server.start();
            assertNotNull(address);
            assertTrue(address.startsWith("http://127.0.0.1:"));

            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            assertEquals(200, connection.getResponseCode());
            assertEquals("no-store, max-age=0", connection.getHeaderField("Cache-Control"));
            try (InputStream input = connection.getInputStream()) {
                String page = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(page.contains("This page can wait."));
                assertTrue(page.contains("Voward"));
                assertFalse(page.contains("<script"));
            }
            connection.disconnect();
        } finally {
            server.close();
        }
    }
}
