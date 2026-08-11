package com.example.voward;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Domain-aware URL matching that avoids matching a hostname inside an unrelated host/query. */
public final class UrlPatternMatcher {
    private UrlPatternMatcher() {}

    public static boolean isValidPattern(String configuredPattern) {
        if (configuredPattern == null) return false;
        String pattern = configuredPattern.trim().toLowerCase(Locale.ROOT);
        if (pattern.startsWith("keyword:")) {
            return !pattern.substring("keyword:".length()).trim().isEmpty();
        }
        URI uri = parseWebUri(pattern);
        if (uri == null || uri.getHost() == null || uri.getHost().isEmpty()) return false;
        String host = uri.getHost();
        return host.contains(".") || host.equals("localhost") || host.contains(":");
    }

    public static boolean matches(String currentUrl, String configuredPattern) {
        if (currentUrl == null || configuredPattern == null) return false;
        String current = currentUrl.trim().toLowerCase(Locale.ROOT);
        String pattern = configuredPattern.trim().toLowerCase(Locale.ROOT);
        if (current.isEmpty() || pattern.isEmpty()) return false;
        if (pattern.startsWith("keyword:")) {
            String keyword = pattern.substring("keyword:".length()).trim();
            return !keyword.isEmpty() && current.contains(keyword);
        }

        URI currentUri = parseWebUri(current);
        URI patternUri = parseWebUri(pattern);
        if (currentUri != null && patternUri != null
                && currentUri.getHost() != null && patternUri.getHost() != null) {
            String currentHost = stripWww(currentUri.getHost());
            String patternHost = stripWww(patternUri.getHost());
            boolean hostMatches = currentHost.equals(patternHost)
                    || currentHost.endsWith("." + patternHost);
            if (!hostMatches) return false;
            String patternPath = patternUri.getPath();
            String patternQuery = patternUri.getRawQuery();
            if (patternQuery != null && !patternQuery.equals(currentUri.getRawQuery())) return false;
            if (patternPath == null || patternPath.isEmpty() || patternPath.equals("/")) return true;
            String currentPath = currentUri.getPath();
            if (currentPath == null) return false;
            String subtree = patternPath.endsWith("/") ? patternPath : patternPath + "/";
            return currentPath.equals(patternPath) || currentPath.startsWith(subtree);
        }

        // Malformed patterns fail safe. Generic matching must be explicitly prefixed keyword:.
        return false;
    }

    private static URI parseWebUri(String value) {
        try {
            String withScheme = hasScheme(value) ? value : "https://" + value;
            return new URI(withScheme);
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean hasScheme(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0) return false;
        for (int i = 0; i < separator; i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '+' && c != '.' && c != '-') return false;
        }
        return true;
    }

    private static String stripWww(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
    }
}
