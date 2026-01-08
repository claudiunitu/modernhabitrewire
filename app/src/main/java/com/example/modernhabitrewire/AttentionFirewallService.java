package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

public class AttentionFirewallService extends AccessibilityService {

    private static final String TAG = "AttentionFirewall";
    private static final String APP_NAME = "Modern Habit Rewire";
    private static final String APP_PACKAGE = "com.example.modernhabitrewire";
    
    private static final List<String> DANGER_PACKAGES = Arrays.asList(
            "com.android.settings", "com.android.packageinstaller", 
            "com.google.android.packageinstaller", "com.android.vending"
    );

    private AppPreferencesManagerSingleton appPreferencesManager;
    private DopamineBudgetEngine dopamineBudgetEngine;
    
    // Sticky Session State: Pauses interception for an approved app until minimized/closed
    private String activeStickyPackage = null;
    private long sessionStartTime = 0;
    
    private List<SupportedBrowserConfig> supportedBrowsers;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        dopamineBudgetEngine = new DopamineBudgetEngine(this);
        this.supportedBrowsers = getSupportedBrowsers();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        int eventType = event.getEventType();

        // 1. SYSTEM & SELF EXCLUSION
        if (packageName.equals(APP_PACKAGE)) return;

        // 2. HARD LOCK (Uninstall/Admin Protection)
        if (appPreferencesManager.getForbidSettingsSwitchValue() && DANGER_PACKAGES.contains(packageName)) {
            if (isDangerZoneActive()) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
        }

        // 3. FIREWALL MASTER TOGGLE
        if (!appPreferencesManager.getIsBlockerActive()) return;

        // 4. APPROVAL HANDLING (Transition from Decision Gate)
        if (appPreferencesManager.getTempAllowAppLaunch()) {
            if (isApprovedPackage(packageName)) {
                Log.d(TAG, "Sticky session START for approved package: " + packageName);
                appPreferencesManager.setTempAllowAppLaunch(false);
                startStickySession(packageName);
                return; // PAUSE INTERCEPTION IMMEDIATELY
            }
            
            if (!event.isFullScreen() || isTransientSystemOverlay(packageName) || isLauncherPackage(packageName)) {
                return; 
            }
            
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Approval discarded. User switched to: " + packageName);
                appPreferencesManager.setTempAllowAppLaunch(false);
            }
        }

        // 5. STICKY SESSION GUARD
        if (activeStickyPackage != null && packageName.equals(activeStickyPackage)) {
            return; 
        }

        // 6. SESSION TERMINATION
        if (activeStickyPackage != null && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName.equals(activeStickyPackage) || !event.isFullScreen() || isTransientSystemOverlay(packageName)) {
                return; 
            }
            Log.d(TAG, "Ending sticky session for " + activeStickyPackage + " due to switch to " + packageName);
            endStickySession();
        }

        // 7. INTERCEPTION LOGIC
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleAppInterception(packageName);
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleUrlInterception(packageName);
        }
    }

    private void handleAppInterception(String packageName) {
        List<String> extractiveApps = appPreferencesManager.getExtractiveAppsPackages();
        if (extractiveApps.contains(packageName)) {
            dopamineBudgetEngine.resetBudgetIfNeeded();
            if (dopamineBudgetEngine.hasBudget()) {
                appPreferencesManager.setLastInterceptedApp(packageName);
                appPreferencesManager.setLastInterceptedUrl(""); 
                triggerDecisionGate();
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }
    }

    private void handleUrlInterception(String packageName) {
        if (activeStickyPackage != null && activeStickyPackage.equals(packageName)) return;

        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                checkBrowserUrl(config);
                break;
            }
        }
    }

    private void checkBrowserUrl(SupportedBrowserConfig config) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        
        AccessibilityNodeInfo bar = null;
        for (String id : config.addressBarIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                bar = nodes.get(0);
                break;
            }
        }
        
        if (bar == null) {
            bar = findUrlBarByContentDescription(root);
        }

        if (bar != null) {
            if (bar.getText() != null) {
                String currentUrl = bar.getText().toString().toLowerCase();
                for (String forbiddenPattern : appPreferencesManager.getForbiddenUrls()) {
                    if (currentUrl.contains(forbiddenPattern.toLowerCase())) {
                        dopamineBudgetEngine.resetBudgetIfNeeded();
                        appPreferencesManager.setLastInterceptedApp(config.packageName);
                        appPreferencesManager.setLastInterceptedUrl(forbiddenPattern);
                        triggerDecisionGate();
                        break;
                    }
                }
            }
            bar.recycle();
        }
        root.recycle();
    }

    private AccessibilityNodeInfo findUrlBarByContentDescription(AccessibilityNodeInfo root) {
        List<String> hints = Arrays.asList("address", "url", "search bar");
        return findNodeByContentDescription(root, hints);
    }

    private AccessibilityNodeInfo findNodeByContentDescription(AccessibilityNodeInfo node, List<String> hints) {
        if (node == null) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            for (String hint : hints) {
                if (d.contains(hint)) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeByContentDescription(child, hints);
            if (result != null) return result;
            if (child != null) child.recycle();
        }
        return null;
    }

    private void startStickySession(String packageName) {
        activeStickyPackage = packageName;
        sessionStartTime = System.currentTimeMillis();
        dopamineBudgetEngine.incrementSessionCount();
    }

    private void endStickySession() {
        if (sessionStartTime != 0) {
            long timeSpent = System.currentTimeMillis() - sessionStartTime;
            dopamineBudgetEngine.depleteBudget(timeSpent);
            activeStickyPackage = null;
            sessionStartTime = 0;
        }
    }

    private boolean isApprovedPackage(String packageName) {
        String lastApp = appPreferencesManager.getLastInterceptedApp();
        return packageName.equals(lastApp);
    }

    private boolean isTransientSystemOverlay(String packageName) {
        String p = packageName.toLowerCase();
        return p.equals("android") || p.contains("systemui") || p.contains("permissioncontroller") ||
               p.contains("inputmethod") || p.contains("latin");
    }

    private boolean isLauncherPackage(String packageName) {
        String p = packageName.toLowerCase();
        return p.contains("launcher") || p.contains("trebuchet") || p.contains("home") || 
               p.contains("nexuslauncher") || p.contains("miui.home");
    }

    private void triggerDecisionGate() {
        Intent intent = new Intent(this, DecisionGateActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private boolean isDangerZoneActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean foundOurApp = findTextRecursive(root, APP_PACKAGE) || findTextRecursive(root, APP_NAME);
        if (foundOurApp) {
            boolean isDestructive = findTextRecursive(root, "info") || findTextRecursive(root, "details") || 
                                    findTextRecursive(root, "storage") || findTextRecursive(root, "admin") || 
                                    findTextRecursive(root, "service") || findTextRecursive(root, "off");
            if (isDestructive) { root.recycle(); return true; }
        }
        root.recycle();
        return false;
    }

    private boolean findTextRecursive(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) return true;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(text.toLowerCase())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findTextRecursive(child, text)) { if (child != null) child.recycle(); return true; }
            if (child != null) child.recycle();
        }
        return false;
    }

    @Override public void onInterrupt() {}

    private static class SupportedBrowserConfig {
        public String packageName;
        public List<String> addressBarIds;
        public SupportedBrowserConfig(String p, String... ids) { 
            this.packageName = p; 
            this.addressBarIds = Arrays.asList(ids); 
        }
    }

    @NonNull
    private List<SupportedBrowserConfig> getSupportedBrowsers() {
        return Arrays.asList(
            new SupportedBrowserConfig("com.android.chrome", 
                "com.android.chrome:id/url_bar", 
                "com.android.chrome:id/url_edit_text"),
            new SupportedBrowserConfig("com.brave.browser", 
                "com.brave.browser:id/url_bar", 
                "com.brave.browser:id/url_edit_text",
                "com.brave.browser:id/location_bar"),
            new SupportedBrowserConfig("org.mozilla.firefox", 
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"),
            new SupportedBrowserConfig("com.opera.browser", 
                "com.opera.browser:id/url_field"),
            new SupportedBrowserConfig("com.duckduckgo.mobile.android", 
                "com.duckduckgo.mobile.android:id/omnibarTextInput")
        );
    }
}
