package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
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
    private String activeExtractiveApp = null;
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

        // 1. HARD LOCK PROTECTION (Exclude our own app to prevent self-blocking)
        if (!packageName.equals(APP_PACKAGE) && appPreferencesManager.getForbidSettingsSwitchValue() && DANGER_PACKAGES.contains(packageName)) {
            if (isDangerZoneActive()) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                Toast.makeText(this, "Protection Active", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // 2. FIREWALL LOGIC
        if (!appPreferencesManager.getIsBlockerActive()) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(packageName);
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleWindowContentChanged(packageName);
        }
    }

    private boolean isDangerZoneActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        boolean foundOurApp = findTextRecursive(root, APP_PACKAGE) || findTextRecursive(root, APP_NAME);
        
        if (foundOurApp) {
            boolean isDestructive = findTextRecursive(root, "info") || 
                                    findTextRecursive(root, "details") || 
                                    findTextRecursive(root, "storage") || 
                                    findTextRecursive(root, "admin") || 
                                    findTextRecursive(root, "service") ||
                                    findTextRecursive(root, "off");
            if (isDestructive) {
                root.recycle();
                return true;
            }
        }
        root.recycle();
        return false;
    }

    private boolean findTextRecursive(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) return true;
        
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(text.toLowerCase())) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findTextRecursive(child, text)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    private void handleWindowStateChanged(String packageName) {
        if (appPreferencesManager.getTempAllowAppLaunch()) {
            if (packageName.equals(appPreferencesManager.getLastInterceptedApp())) {
                appPreferencesManager.setTempAllowAppLaunch(false);
                activeExtractiveApp = packageName;
                sessionStartTime = System.currentTimeMillis();
                dopamineBudgetEngine.incrementSessionCount();
                return;
            }
            if (packageName.equals("com.android.systemui") || packageName.contains("launcher")) return;
        }

        if (activeExtractiveApp != null && !activeExtractiveApp.equals(packageName)) {
            if (!packageName.equals("com.android.systemui") && !packageName.equals(getPackageName())) {
                endActiveSession();
            }
        }

        List<String> extractiveApps = appPreferencesManager.getExtractiveAppsPackages();
        if (extractiveApps.contains(packageName) && !packageName.equals(activeExtractiveApp)) {
            dopamineBudgetEngine.resetBudgetIfNeeded();
            if (dopamineBudgetEngine.hasBudget()) {
                appPreferencesManager.setLastInterceptedApp(packageName);
                Intent intent = new Intent(this, DecisionGateActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }
    }

    private void handleWindowContentChanged(String packageName) {
        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                checkBrowserUrl(config);
                break;
            }
        }
    }

    private void endActiveSession() {
        if (activeExtractiveApp != null) {
            long timeSpent = System.currentTimeMillis() - sessionStartTime;
            dopamineBudgetEngine.depleteBudget(timeSpent);
            activeExtractiveApp = null;
            sessionStartTime = 0;
        }
    }

    private void checkBrowserUrl(SupportedBrowserConfig config) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(config.addressBarId);
        if (nodes != null && !nodes.isEmpty()) {
            AccessibilityNodeInfo bar = nodes.get(0);
            if (bar.getText() != null) {
                String url = bar.getText().toString();
                for (String pattern : appPreferencesManager.getForbiddenUrls()) {
                    if (url.contains(pattern)) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        break;
                    }
                }
            }
            bar.recycle();
        }
        root.recycle();
    }

    @Override public void onInterrupt() {}

    private static class SupportedBrowserConfig {
        public String packageName, addressBarId;
        public SupportedBrowserConfig(String p, String a) { this.packageName = p; this.addressBarId = a; }
    }

    @NonNull
    private List<SupportedBrowserConfig> getSupportedBrowsers() {
        return Arrays.asList(
            new SupportedBrowserConfig("com.android.chrome", "com.android.chrome:id/url_bar"),
            new SupportedBrowserConfig("com.brave.browser", "com.brave.browser:id/url_bar"),
            new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
            new SupportedBrowserConfig("com.opera.browser", "com.opera.browser:id/url_field"),
            new SupportedBrowserConfig("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput")
        );
    }
}
