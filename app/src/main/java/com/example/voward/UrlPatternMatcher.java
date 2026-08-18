package com.example.voward;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Domain-aware URL matching that avoids matching a hostname inside an unrelated host/query. */
public final class UrlPatternMatcher {
    /** Internal-only rule used when persisted URL rules cannot be decoded safely. */
    static final String FAIL_CLOSED_PATTERN = "\u0000voward-corrupt-url-rules";

    private UrlPatternMatcher() {}

    public static boolean isValidPattern(String configuredPattern) {
        if (configuredPattern == null) return false;
        String pattern = configuredPattern.trim().toLowerCase(Locale.ROOT);
        if (pattern.startsWith("keyword:")) {
            return !pattern.substring("keyword:".length()).trim().isEmpty();
        }
        URI uri = parseWebUri(pattern);
        String host = canonicalHost(uri);
        if (host == null || host.isEmpty()) return false;
        return host.contains(".") || host.equals("localhost") || host.contains(":");
    }

    public static boolean matches(String currentUrl, String configuredPattern) {
        if (currentUrl == null || configuredPattern == null) return false;
        if (FAIL_CLOSED_PATTERN.equals(configuredPattern)) {
            return !currentUrl.trim().isEmpty();
        }
        String current = currentUrl.trim().toLowerCase(Locale.ROOT);
        String pattern = configuredPattern.trim().toLowerCase(Locale.ROOT);
        if (current.isEmpty() || pattern.isEmpty()) return false;
        if (pattern.startsWith("keyword:")) {
            String keyword = pattern.substring("keyword:".length()).trim();
            return !keyword.isEmpty() && current.contains(keyword);
        }

        URI currentUri = parseWebUri(current);
        URI patternUri = parseWebUri(pattern);
        if (currentUri != null && patternUri != null) {
            String currentHost = canonicalHost(currentUri);
            String patternHost = canonicalHost(patternUri);
            if (currentHost == null || patternHost == null) return false;
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

    /** Returns a comparison-safe DNS hostname, including Unicode and absolute-name forms. */
    private static String canonicalHost(URI uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        if (host == null) host = hostFromAuthority(uri.getRawAuthority());
        if (host == null || host.isEmpty()) return null;

        String normalized = host.toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            try {
                normalized = IDN.toASCII(normalized).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException invalidHostname) {
                return null;
            }
        }
        // A final dot denotes the same absolute DNS name (example.com. == example.com).
        if (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
    }

    /** URI#getHost returns null for Unicode authorities, so recover their host safely. */
    private static String hostFromAuthority(String authority) {
        if (authority == null || authority.isEmpty()) return null;
        String host = authority;
        int userInfo = host.lastIndexOf('@');
        if (userInfo >= 0) host = host.substring(userInfo + 1);
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            return closingBracket > 0 ? host.substring(1, closingBracket) : null;
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            String port = host.substring(firstColon + 1);
            if (port.isEmpty()) return null;
            for (int i = 0; i < port.length(); i++) {
                if (!Character.isDigit(port.charAt(i))) return null;
            }
            host = host.substring(0, firstColon);
        }
        return host;
    }
}
