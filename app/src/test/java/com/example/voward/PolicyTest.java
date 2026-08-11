package com.example.voward;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PolicyTest {
    @Test
    public void committedUrlMustBeVisibleUnfocusedAndUnchanged() {
        List<String> rules = List.of("example.com");
        assertNull(BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "example.com", "example.com", true, rules));
        assertNull(BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                null, "example.com", false, rules));
        assertNull(BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "example.com", "other.test", false, rules));
        assertEquals("example.com", BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                " HTTPS://EXAMPLE.COM ", "https://example.com", false, rules));
        assertNull(BrowserUrlEnforcementPolicy.findCommittedRestrictedPattern(
                "example.com", "example.com", false, null));
    }

    @Test
    public void strictUrlRulesAreEvaluatedBeforeRegularRules() {
        BrowserUrlEnforcementPolicy.RuleMatch strict =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                        "example.com/video/1", "example.com/video/1", false,
                        List.of("example.com/video"), List.of("example.com"));
        assertNotNull(strict);
        assertEquals("example.com/video", strict.pattern);
        assertTrue(strict.strict);

        BrowserUrlEnforcementPolicy.RuleMatch regular =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                        "example.com/news", "example.com/news", false,
                        Collections.emptyList(), List.of("example.com"));
        assertNotNull(regular);
        assertFalse(regular.strict);
    }

    @Test
    public void interceptionPolicyDistinguishesWholeBrowserFromUrlSessions() {
        assertTrue(InterceptionPolicy.shouldStartSessionTimer("APP", true, false));
        assertTrue(InterceptionPolicy.shouldStartSessionTimer("URL", false, false));
        assertTrue(InterceptionPolicy.shouldStartSessionTimer("URL", true, true));
        assertFalse(InterceptionPolicy.shouldStartSessionTimer("URL", true, false));
        assertFalse(InterceptionPolicy.shouldStartSessionTimer(null, true, false));

        assertTrue(InterceptionPolicy.isApprovedWholeBrowserSession("APP", true, true));
        assertFalse(InterceptionPolicy.isApprovedWholeBrowserSession("URL", true, true));
        assertFalse(InterceptionPolicy.isApprovedWholeBrowserSession("APP", false, true));
        assertFalse(InterceptionPolicy.isApprovedWholeBrowserSession("APP", true, false));
    }

    @Test
    public void decisionGateRoundsUpAndSaturates() {
        assertEquals(0, DecisionGatePolicy.remainingSeconds(1_000, 1_000));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(1_000, 2_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(1_001, 1_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(1_999, 1_000));
        assertEquals(2, DecisionGatePolicy.remainingSeconds(2_000, 0));
        assertEquals(Integer.MAX_VALUE,
                DecisionGatePolicy.remainingSeconds(Long.MAX_VALUE, 0));
    }

    @Test
    public void safetyPolicyProtectsCoreOwnAndRecoveryPackages() {
        assertTrue(SafetyPolicy.isCriticalPackage(null, "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage(" ", "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage("COM.EXAMPLE.VOWARD", "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage("com.android.settings", "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage("vendor.emergency.ui", "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage("vendor.phone.dialer", "com.example.voward"));
        assertTrue(SafetyPolicy.isCriticalPackage("vendor.telecom", "com.example.voward"));
        assertFalse(SafetyPolicy.isCriticalPackage("com.example.social", "com.example.voward"));
    }

    @Test
    public void browserCatalogIsImmutableUniqueAndSupportsDiscovery() {
        assertTrue(BrowserSupport.all().size() >= 30);
        assertEquals(BrowserSupport.all().size(), BrowserSupport.all().stream()
                .map(config -> config.packageName).distinct().count());
        assertNull(BrowserSupport.find(null));
        assertNull(BrowserSupport.find("unknown"));

        BrowserSupport.Config firefox = BrowserSupport.find("org.mozilla.firefox");
        assertNotNull(firefox);
        assertEquals("about:home", firefox.safeAddress);
        assertTrue(BrowserSupport.isConfiguredSafeAddress(firefox, " ABOUT:HOME/ "));
        assertFalse(BrowserSupport.isConfiguredSafeAddress(null, "about:home"));
        assertFalse(BrowserSupport.isConfiguredSafeAddress(firefox, null));

        List<BrowserSupport.Config> merged = BrowserSupport.withDiscoveredPackages(
                Arrays.asList(null, "", "com.android.chrome", "org.new.browser", "org.new.browser"));
        assertEquals(BrowserSupport.all().size() + 1, merged.size());
        BrowserSupport.Config added = merged.get(merged.size() - 1);
        assertEquals("org.new.browser", added.packageName);
        assertTrue(added.addressBarIds.contains("org.new.browser:id/url_bar"));

        List<BrowserSupport.Config> redirected = BrowserSupport.withDiscoveredPackages(
                List.of("org.new.browser"), "http://127.0.0.1:1234/blocked");
        assertTrue(redirected.stream().allMatch(
                config -> "http://127.0.0.1:1234/blocked".equals(config.safeAddress)));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void browserCatalogCannotBeMutated() {
        BrowserSupport.all().clear();
    }
}
