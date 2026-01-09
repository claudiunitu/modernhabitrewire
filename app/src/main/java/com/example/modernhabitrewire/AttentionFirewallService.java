package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    
    // Sticky Session State
    private String activeStickyPackage = null;
    
    // Cost Tracking Logic: Accumulate only forbidden time
    private final Set<String> sessionApprovedPatterns = new HashSet<>();
    private long accumulatedForbiddenTimeMs = 0;
    private long lastForbiddenStartTime = 0;
    
    private final Set<String> installedImePackages = new HashSet<>();
    private List<SupportedBrowserConfig> supportedBrowsers;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        dopamineBudgetEngine = new DopamineBudgetEngine(this);
        this.supportedBrowsers = getSupportedBrowsers();
        
        refreshImeList();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    private void refreshImeList() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> imis = imm.getInputMethodList();
                for (InputMethodInfo imi : imis) {
                    installedImePackages.add(imi.getPackageName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh IME list", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        int eventType = event.getEventType();

        // 1. SYSTEM & SELF EXCLUSION
        if (packageName.equals(APP_PACKAGE)) {
            // Pause timer while user is in the Decision Gate or Settings
            updateForbiddenTimer(false);
            return;
        }

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
                return; 
            }
            
            if (!event.isFullScreen() || isTransientSystemOverlay(packageName) || isLauncherPackage(packageName)) {
                return; 
            }
            
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                appPreferencesManager.setTempAllowAppLaunch(false);
            }
        }

        // 5. SESSION TERMINATION
        if (activeStickyPackage != null && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!packageName.equals(activeStickyPackage) && !isTransientSystemOverlay(packageName)) {
                if (isLauncherPackage(packageName) || event.isFullScreen()) {
                    Log.d(TAG, "Ending session for " + activeStickyPackage + " due to switch to " + packageName);
                    endStickySession();
                }
            }
        }

        // 6. INTERCEPTION & MONITORING
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleAppInterception(packageName);
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleUrlInterception(packageName);
        }
        
        // 7. LIVE BUDGET ENFORCEMENT
        if (activeStickyPackage != null) {
            checkLiveBudgetExhaustion();
        }
    }

    private void handleAppInterception(String packageName) {
        List<String> extractiveApps = appPreferencesManager.getExtractiveAppsPackages();
        if (extractiveApps.contains(packageName)) {
            if (activeStickyPackage != null && activeStickyPackage.equals(packageName)) {
                updateForbiddenTimer(true);
                return;
            }

            dopamineBudgetEngine.resetBudgetIfNeeded();
            if (dopamineBudgetEngine.hasBudget()) {
                appPreferencesManager.setLastInterceptedApp(packageName);
                appPreferencesManager.setLastInterceptedUrl(""); 
                triggerDecisionGate();
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        } else {
            if (activeStickyPackage != null && packageName.equals(activeStickyPackage)) {
                updateForbiddenTimer(false);
            }
        }
    }

    private void handleUrlInterception(String packageName) {
        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                checkBrowserUrl(config);
                return;
            }
        }
        if (activeStickyPackage != null && packageName.equals(activeStickyPackage)) {
            updateForbiddenTimer(false);
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

        if (bar != null && bar.getText() != null) {
            String currentUrl = bar.getText().toString().toLowerCase();
            String matchedPattern = null;
            for (String pattern : appPreferencesManager.getForbiddenUrls()) {
                if (currentUrl.contains(pattern.toLowerCase())) {
                    matchedPattern = pattern;
                    break;
                }
            }

            if (matchedPattern != null) {
                updateForbiddenTimer(true);
                if (activeStickyPackage != null && config.packageName.equals(activeStickyPackage)) {
                    if (!sessionApprovedPatterns.contains(matchedPattern)) {
                        appPreferencesManager.setLastInterceptedApp(config.packageName);
                        appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                        triggerDecisionGate();
                    }
                } else {
                    appPreferencesManager.setLastInterceptedApp(config.packageName);
                    appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                    triggerDecisionGate();
                }
            } else {
                updateForbiddenTimer(false);
            }
            bar.recycle();
        }
        root.recycle();
    }

    private void updateForbiddenTimer(boolean isForbidden) {
        long now = System.currentTimeMillis();
        if (isForbidden) {
            if (lastForbiddenStartTime == 0) {
                lastForbiddenStartTime = now;
            }
        } else {
            if (lastForbiddenStartTime != 0) {
                accumulatedForbiddenTimeMs += (now - lastForbiddenStartTime);
                lastForbiddenStartTime = 0;
            }
        }
    }

    private void checkLiveBudgetExhaustion() {
        if (lastForbiddenStartTime == 0 && accumulatedForbiddenTimeMs == 0) return;

        long currentForbiddenSegment = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
        long totalForbiddenTime = accumulatedForbiddenTimeMs + currentForbiddenSegment;
        
        double multiplier = dopamineBudgetEngine.calculateCurrentMultiplier();
        long virtualCostMs = (long) (totalForbiddenTime * multiplier);
        long remainingBudgetMs = appPreferencesManager.getRemainingDopamineBudgetMs();

        if (virtualCostMs >= remainingBudgetMs) {
            Log.d(TAG, "LIVE BUDGET EXHAUSTED. Kicking user out.");
            performGlobalAction(GLOBAL_ACTION_HOME);
            endStickySession();
        }
    }

    private void startStickySession(String packageName) {
        String interceptedUrl = appPreferencesManager.getLastInterceptedUrl();
        
        if (activeStickyPackage != null && activeStickyPackage.equals(packageName)) {
            if (!interceptedUrl.isEmpty()) sessionApprovedPatterns.add(interceptedUrl);
            return;
        }

        activeStickyPackage = packageName;
        sessionApprovedPatterns.clear();
        if (!interceptedUrl.isEmpty()) sessionApprovedPatterns.add(interceptedUrl);
        
        accumulatedForbiddenTimeMs = 0;
        lastForbiddenStartTime = System.currentTimeMillis();
        dopamineBudgetEngine.incrementSessionCount();
    }

    private void endStickySession() {
        updateForbiddenTimer(false);
        if (accumulatedForbiddenTimeMs > 0) {
            dopamineBudgetEngine.depleteBudget(accumulatedForbiddenTimeMs);
        }
        activeStickyPackage = null;
        sessionApprovedPatterns.clear();
        accumulatedForbiddenTimeMs = 0;
        lastForbiddenStartTime = 0;
    }

    private boolean isApprovedPackage(String packageName) {
        String lastApp = appPreferencesManager.getLastInterceptedApp();
        return packageName.equals(lastApp);
    }

    private boolean isTransientSystemOverlay(String packageName) {
        if (installedImePackages.contains(packageName)) return true;
        String p = packageName.toLowerCase();
        return p.equals("android") || p.contains("systemui") || p.contains("permissioncontroller") ||
               p.contains("inputmethod") || p.contains("latin") || p.contains("keyboard") || 
               p.contains("board") || p.contains("ime") || p.equals("com.android.settings");
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

    private AccessibilityNodeInfo findUrlBarByContentDescription(AccessibilityNodeInfo root) {
        return findNodeByContentDescription(root, Arrays.asList("address", "url", "search bar"));
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
