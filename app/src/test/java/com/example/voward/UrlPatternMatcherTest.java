package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UrlPatternMatcherTest {
    @Test
    public void validationAcceptsDomainsPathsQueriesLocalhostAndKeywords() {
        assertTrue(UrlPatternMatcher.isValidPattern("example.com"));
        assertTrue(UrlPatternMatcher.isValidPattern(" HTTPS://EXAMPLE.COM/news?q=one "));
        assertTrue(UrlPatternMatcher.isValidPattern("localhost:8080/path"));
        assertTrue(UrlPatternMatcher.isValidPattern("keyword: shorts "));
    }

    @Test
    public void validationRejectsEmptyAmbiguousAndMalformedRules() {
        assertFalse(UrlPatternMatcher.isValidPattern(null));
        assertFalse(UrlPatternMatcher.isValidPattern(""));
        assertFalse(UrlPatternMatcher.isValidPattern("keyword:   "));
        assertFalse(UrlPatternMatcher.isValidPattern("shorts"));
        assertFalse(UrlPatternMatcher.isValidPattern("https://"));
        assertFalse(UrlPatternMatcher.isValidPattern("exa mple.com"));
        assertFalse(UrlPatternMatcher.isValidPattern("example.com:not-a-port"));
    }

    @Test
    public void domainMatchingIsCaseInsensitiveAndHonorsLabelBoundaries() {
        assertTrue(UrlPatternMatcher.matches(" HTTPS://WWW.Example.COM/ ", "example.com"));
        assertTrue(UrlPatternMatcher.matches("https://deep.sub.example.com", "www.example.com"));
        assertTrue(UrlPatternMatcher.matches("https://example.com./news", "example.com"));
        assertTrue(UrlPatternMatcher.matches("https://bücher.example/", "xn--bcher-kva.example"));
        assertFalse(UrlPatternMatcher.matches("https://notexample.com", "example.com"));
        assertFalse(UrlPatternMatcher.matches("https://example.com.evil.test", "example.com"));
        assertFalse(UrlPatternMatcher.matches("https://safe.test/?next=example.com", "example.com"));
    }

    @Test
    public void pathsMatchOnlyTheConfiguredSubtree() {
        assertTrue(UrlPatternMatcher.matches("example.com/news", "example.com/news"));
        assertTrue(UrlPatternMatcher.matches("example.com/news/world", "example.com/news"));
        assertTrue(UrlPatternMatcher.matches("example.com/news/world", "example.com/news/"));
        assertFalse(UrlPatternMatcher.matches("example.com/newspaper", "example.com/news"));
        assertFalse(UrlPatternMatcher.matches("example.com/NEWS", "example.com/other"));
    }

    @Test
    public void queryRulesRequireTheExactRawQuery() {
        assertTrue(UrlPatternMatcher.matches("example.com/search?q=focus", "example.com/search?q=focus"));
        assertFalse(UrlPatternMatcher.matches("example.com/search?q=FOCUS&sort=new", "example.com/search?q=focus"));
        assertFalse(UrlPatternMatcher.matches("example.com/search?q=noise", "example.com/search?q=focus"));
    }

    @Test
    public void keywordRulesAreExplicitBroadMatches() {
        assertTrue(UrlPatternMatcher.matches("https://safe.test/Shorts?id=1", " keyword:shorts "));
        assertFalse(UrlPatternMatcher.matches("https://safe.test/video", "keyword:shorts"));
        assertFalse(UrlPatternMatcher.matches("https://safe.test/shorts", "shorts"));
        assertFalse(UrlPatternMatcher.matches(null, "keyword:x"));
        assertFalse(UrlPatternMatcher.matches("x", null));
    }
}
