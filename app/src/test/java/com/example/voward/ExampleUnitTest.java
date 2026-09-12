package com.example.voward;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(41, BudgetMath.calculateReentryWaitSeconds(30, .35, 1));
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
        Set<String> resolved = Set.of("org.vendor.emergency");
        assertTrue(SafetyPolicy.isCriticalPackage("com.android.dialer", "my.app", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage("my.app", "my.app", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage("org.vendor.emergency", "my.app", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage("com.example.social", "my.app", resolved));
    }

    @Test
    public void gateCountdownUsesAbsoluteDeadlineAcrossRecreation() {
        assertEquals(30, DecisionGatePolicy.remainingSeconds(40_000, 10_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(40_000, 39_001));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(40_000, 40_000));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(40_000, 50_000));
    }

}
