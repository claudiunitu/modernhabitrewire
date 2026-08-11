package com.example.voward;

/** Pure distinction between whole-app and URL-only browser approvals. */
public final class InterceptionPolicy {
    private InterceptionPolicy() {}

    public static boolean shouldStartSessionTimer(
            String interceptionKind, boolean browserPackage, boolean hasInterceptedUrl) {
        return "APP".equals(interceptionKind) || !browserPackage || hasInterceptedUrl;
    }

    public static boolean isApprovedWholeBrowserSession(
            String interceptionKind, boolean sameActivePackage, boolean browserPackage) {
        return browserPackage && sameActivePackage && "APP".equals(interceptionKind);
    }
}
