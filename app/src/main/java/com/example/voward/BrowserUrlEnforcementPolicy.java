package com.example.voward;

import java.util.List;
import java.util.Locale;

/** Pure commit policy shared by immediate and deferred browser URL observations. */
public final class BrowserUrlEnforcementPolicy {
    private BrowserUrlEnforcementPolicy() {}

    /** A committed URL rule match, including whether budget can ever unlock it. */
    public static final class RuleMatch {
        public final String pattern;
        public final boolean strict;

        RuleMatch(String pattern, boolean strict) {
            this.pattern = pattern;
            this.strict = strict;
        }
    }

    /** Returns a matching rule only after the browser address is visibly committed. */
    public static String findCommittedRestrictedPattern(
            String observedUrl,
            String currentlyVisibleUrl,
            boolean addressBarFocused,
            List<String> restrictedPatterns) {
        RuleMatch match = findCommittedRestrictedMatch(
                observedUrl, currentlyVisibleUrl, addressBarFocused, null, restrictedPatterns);
        return match == null ? null : match.pattern;
    }

    /**
     * Resolves strict patterns first so a broad regular rule can never mask a more specific
     * strict rule for the same committed URL.
     */
    public static RuleMatch findCommittedRestrictedMatch(
            String observedUrl,
            String currentlyVisibleUrl,
            boolean addressBarFocused,
            List<String> strictPatterns,
            List<String> restrictedPatterns) {
        if (addressBarFocused) return null;

        String observed = normalize(observedUrl);
        String visible = normalize(currentlyVisibleUrl);
        if (observed.isEmpty() || !observed.equals(visible)) return null;

        if (strictPatterns != null) {
            for (String pattern : strictPatterns) {
                if (UrlPatternMatcher.matches(visible, pattern)) {
                    return new RuleMatch(pattern, true);
                }
            }
        }
        if (restrictedPatterns != null) {
            for (String pattern : restrictedPatterns) {
                if (UrlPatternMatcher.matches(visible, pattern)) {
                    return new RuleMatch(pattern, false);
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
