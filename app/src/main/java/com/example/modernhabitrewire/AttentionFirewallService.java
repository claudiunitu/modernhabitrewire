package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AttentionFirewallService extends AccessibilityService {

    private static final String TAG = "AttentionFirewall";
    private static final String APP_NAME = "Modern Habit Rewire";
    private static final String APP_PACKAGE = "com.example.modernhabitrewire";
    private static final String CHANNEL_ID = "firewall_stats_channel";
    private static final int NOTIFICATION_ID = 1;
    
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
    private boolean isForbiddenConfirmed = false;
    
    // Forced Cleanup / Lockout Logic
    private boolean isBudgetLockedOut = false;
    private boolean wasNegativeAtSessionStart = false; 
    
    // Foreground Ownership Tracking
    private String lastForegroundPackage = null;
    private long lastForegroundChangeTime = 0;
    private static final long FOREGROUND_DEBOUNCE_MS = 300;

    // URL Stability & Cooldown Logic
    private final Map<String, String> lastObservedUrls = new HashMap<>();
    private final Map<String, Long> lastUrlChangeTimes = new HashMap<>();
    private static final long URL_STABLE_MS = 800;
    
    // Shared cooldown to prevent browser loops
    private static long lastDecisionGateTime = 0;
    private static final long DECISION_COOLDOWN_MS = 5000;

    private final Set<String> installedImePackages = new HashSet<>();
    private final Set<String> launcherPackages = new HashSet<>();
    private List<SupportedBrowserConfig> supportedBrowsers;

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private final Runnable notificationTicker = new Runnable() {
        @Override
        public void run() {
            updateStatsNotification();
            if (lastForbiddenStartTime != 0) {
                notificationHandler.postDelayed(this, 1000);
            }
        }
    };

    // Friction Handler for Overdraw
    private boolean isFrictionRunning = false;
    private final Handler frictionHandler = new Handler(Looper.getMainLooper());
    private final Runnable frictionTicker = new Runnable() {
        @Override
        public void run() {
            // Apply friction if the budget is <= 0 AND we are confirmed in forbidden zone
            if (activeStickyPackage != null && dopamineBudgetEngine.getRemainingBudget() <= 0 && isForbiddenConfirmed) {
                Log.d(TAG, "Applying overdraw friction (hiccup)");
                performGlobalAction(GLOBAL_ACTION_BACK);
                frictionHandler.postDelayed(this, 15000); 
            } else {
                isFrictionRunning = false;
            }
        }
    };

    public static void notifyGateClosed() {
        lastDecisionGateTime = System.currentTimeMillis();
        Log.d(TAG, "Gate closure notified. Cooldown reset.");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        dopamineBudgetEngine = new DopamineBudgetEngine(this);
        this.supportedBrowsers = getSupportedBrowsers();
        
        refreshImeList();
        refreshLauncherList();
        createNotificationChannel();
        updateStatsNotification();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), 
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void updateStatsNotification() {
        boolean active = appPreferencesManager.getIsBlockerActive();
        long remainingUnits = dopamineBudgetEngine.getRemainingBudget();
        
        long sessionForbiddenUnits = 0;
        double currentInstantMultiplier = dopamineBudgetEngine.calculateCurrentMultiplier();
        
        if (activeStickyPackage != null) {
            long currentSegmentMs = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
            long totalMs = accumulatedForbiddenTimeMs + currentSegmentMs;
            
            sessionForbiddenUnits = dopamineBudgetEngine.calculateEscalatedCost(totalMs);
            currentInstantMultiplier = dopamineBudgetEngine.calculateInstantaneousMultiplier(totalMs);
        }

        String status = active ? getString(R.string.blocker_active) : getString(R.string.blocker_inactive);
        String stats = getString(R.string.notification_stats_template, 
                remainingUnits, sessionForbiddenUnits, currentInstantMultiplier);

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(status)
                .setContentText(stats)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
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

    private void refreshLauncherList() {
        launcherPackages.clear();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null) {
                launcherPackages.add(info.activityInfo.packageName);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        int eventType = event.getEventType();

        if (packageName.equals(APP_PACKAGE)) {
            updateStatsNotification();
            return;
        }

        if (appPreferencesManager.getForbidSettingsSwitchValue() && DANGER_PACKAGES.contains(packageName)) {
            if (isDangerZoneActive()) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
        }

        if (!appPreferencesManager.getIsBlockerActive()) {
            updateStatsNotification();
            return;
        }

        long now = System.currentTimeMillis();
        boolean isNewForeground = lastForegroundPackage == null || !packageName.equals(lastForegroundPackage);

        if (isNewForeground && (now - lastForegroundChangeTime > FOREGROUND_DEBOUNCE_MS)) {
            lastForegroundPackage = packageName;
            lastForegroundChangeTime = now;
            onForegroundAppChanged(packageName);
        }

        if (appPreferencesManager.getTempAllowAppLaunch()) {
            if (isApprovedPackage(packageName)) {
                appPreferencesManager.setTempAllowAppLaunch(false);
                startStickySession(packageName);
                return; 
            }
            if (isTransientSystemOverlay(packageName) || isLauncherPackage(packageName)) return; 
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) appPreferencesManager.setTempAllowAppLaunch(false);
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean isTransient = isTransientSystemOverlay(packageName);
            boolean isLauncher = isLauncherPackage(packageName);
            boolean isExtractive = appPreferencesManager.getExtractiveAppsPackages().contains(packageName);
            
            if (activeStickyPackage != null) {
                if (!packageName.equals(activeStickyPackage) && !isTransient) {
                    endStickySession();
                    if (!isExtractive && !isLauncher) isBudgetLockedOut = false;
                }
            } else if (!isTransient && !isLauncher && !isExtractive) {
                isBudgetLockedOut = false;
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleUrlInterception(packageName, eventType);
        }
        
        if (activeStickyPackage != null) {
            checkLiveBudgetExhaustion();
        }

        updateStatsNotification();
    }

    private void onForegroundAppChanged(String packageName) {
        if (isTransientSystemOverlay(packageName) || isLauncherPackage(packageName)) return;
        handleAppInterception(packageName);
    }

    private void handleAppInterception(String packageName) {
        List<String> extractiveApps = appPreferencesManager.getExtractiveAppsPackages();
        if (extractiveApps.contains(packageName)) {
            if (activeStickyPackage != null && packageName.equals(activeStickyPackage)) {
                updateForbiddenTimer(true);
                return;
            }
            if (dopamineBudgetEngine.getRemainingBudget() <= 0 && !isApprovedPackage(packageName)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                triggerDecisionGate();
                return;
            }
            dopamineBudgetEngine.resetBudgetIfNeeded();
            appPreferencesManager.setLastInterceptedApp(packageName);
            appPreferencesManager.setLastInterceptedUrl(""); 
            triggerDecisionGate();
        }
    }

    private void handleUrlInterception(String packageName, int eventType) {
        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                checkBrowserUrl(config, eventType);
                return;
            }
        }
    }

    private void checkBrowserUrl(SupportedBrowserConfig config, int eventType) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (root.getPackageName() == null || !root.getPackageName().toString().equals(config.packageName)) return;

        AccessibilityNodeInfo bar = null;
        for (String id : config.addressBarIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                bar = nodes.get(0);
                break;
            }
        }
        if (bar == null) bar = findUrlBarByContentDescription(root);

        if (bar != null && bar.getText() != null) {
            String currentUrl = bar.getText().toString().toLowerCase().trim();
            long now = System.currentTimeMillis();

            String prev = lastObservedUrls.get(config.packageName);
            if (!currentUrl.equals(prev)) {
                lastObservedUrls.put(config.packageName, currentUrl);
                lastUrlChangeTimes.put(config.packageName, now);
                return; 
            }

            long lastChange = lastUrlChangeTimes.getOrDefault(config.packageName, 0L);
            if (now - lastChange < URL_STABLE_MS) return; 

            String matchedPattern = null;
            for (String pattern : appPreferencesManager.getForbiddenUrls()) {
                String cleanPattern = pattern.toLowerCase().trim();
                if (!cleanPattern.isEmpty() && currentUrl.contains(cleanPattern)) {
                    matchedPattern = pattern;
                    break;
                }
            }

            boolean committed = !bar.isFocused() || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

            if (matchedPattern != null && committed) {
                if (System.currentTimeMillis() - lastDecisionGateTime < DECISION_COOLDOWN_MS) return;

                if (dopamineBudgetEngine.getRemainingBudget() <= 0 && activeStickyPackage == null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    triggerDecisionGate();
                    return;
                }

                if (activeStickyPackage != null && config.packageName.equals(activeStickyPackage)) {
                    updateForbiddenTimer(true);
                    if (!sessionApprovedPatterns.contains(matchedPattern)) {
                        appPreferencesManager.setLastInterceptedApp(config.packageName);
                        appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                        triggerDecisionGate();
                    }
                } else if (activeStickyPackage == null) {
                    dopamineBudgetEngine.resetBudgetIfNeeded();
                    appPreferencesManager.setLastInterceptedApp(config.packageName);
                    appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                    triggerDecisionGate();
                }
            } else {
                if (matchedPattern == null && activeStickyPackage != null && !bar.isFocused()) {
                    isBudgetLockedOut = false;
                    confirmSafeState(config.packageName);
                }
            }
        }
    }

    private void confirmSafeState(String packageName) {
        if (!isForbiddenConfirmed) return;
        isForbiddenConfirmed = false;
        lastObservedUrls.remove(packageName);
        updateForbiddenTimer(false);
    }

    private void updateForbiddenTimer(boolean isForbidden) {
        if (activeStickyPackage == null) isForbidden = false;
        long now = System.currentTimeMillis();

        if (isForbidden) {
            isForbiddenConfirmed = true;
            if (lastForbiddenStartTime == 0) {
                lastForbiddenStartTime = now;
                notificationHandler.post(notificationTicker);
            }
            // Logic Fixed: Only post if friction is not already running
            if (dopamineBudgetEngine.getRemainingBudget() <= 0 && !isFrictionRunning) {
                isFrictionRunning = true;
                frictionHandler.removeCallbacks(frictionTicker);
                frictionHandler.postDelayed(frictionTicker, 15000);
            }
        } else {
            if (lastForbiddenStartTime != 0) {
                accumulatedForbiddenTimeMs += (now - lastForbiddenStartTime);
                lastForbiddenStartTime = 0;
                notificationHandler.removeCallbacks(notificationTicker);
                
                isFrictionRunning = false;
                frictionHandler.removeCallbacks(frictionTicker);
            }
        }
    }

    private void checkLiveBudgetExhaustion() {
        if (activeStickyPackage == null || isBudgetLockedOut || wasNegativeAtSessionStart) return;
        
        long remainingUnits = dopamineBudgetEngine.getRemainingBudget();
        long currentForbiddenSegmentMs = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
        long totalForbiddenTimeMs = accumulatedForbiddenTimeMs + currentForbiddenSegmentMs;
        
        long unitCost = dopamineBudgetEngine.calculateEscalatedCost(totalForbiddenTimeMs);

        if (remainingUnits <= 0 || unitCost >= remainingUnits) {
            isBudgetLockedOut = true;
            appPreferencesManager.setLastInterceptedApp(activeStickyPackage);
            endStickySession();
            triggerDecisionGate();
        }
    }

    private void startStickySession(String packageName) {
        isBudgetLockedOut = false; 
        wasNegativeAtSessionStart = (dopamineBudgetEngine.getRemainingBudget() <= 0);

        activeStickyPackage = packageName;
        sessionApprovedPatterns.clear();
        String interceptedUrl = appPreferencesManager.getLastInterceptedUrl();
        if (!interceptedUrl.isEmpty()) sessionApprovedPatterns.add(interceptedUrl);
        
        accumulatedForbiddenTimeMs = 0;
        isForbiddenConfirmed = false;
        lastForbiddenStartTime = 0; 
        
        updateForbiddenTimer(true);
        
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
        wasNegativeAtSessionStart = false; 
        isFrictionRunning = false;
        frictionHandler.removeCallbacks(frictionTicker);
        notificationHandler.removeCallbacks(notificationTicker);
        updateStatsNotification();
    }

    private boolean isApprovedPackage(String packageName) {
        String lastApp = appPreferencesManager.getLastInterceptedApp();
        return packageName.equals(lastApp);
    }

    private boolean isTransientSystemOverlay(String packageName) {
        if (packageName == null) return false;
        if (installedImePackages.contains(packageName)) return true;
        return isSystemUiOverlay(packageName) || 
               packageName.contains("permissioncontroller") || packageName.contains("inputmethod") || 
               packageName.contains("latin") || packageName.contains("keyboard") || 
               packageName.contains("board") || packageName.contains("ime");
    }

    private boolean isSystemUiOverlay(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase();
        return p.equals("android") || p.contains("systemui") || p.contains("statusbar") || 
               p.contains("notification") || p.contains("quicksettings");
    }

    private boolean isLauncherPackage(String packageName) {
        if (packageName == null) return false;
        if (launcherPackages.contains(packageName)) return true;
        String p = packageName.toLowerCase();
        return p.contains("launcher") || p.contains("trebuchet") || p.contains("home") || 
               p.contains("nexuslauncher") || p.contains("miui.home") || p.contains("pixel") ||
               p.contains("launcher3") || p.contains("launcher2");
    }

    private void triggerDecisionGate() {
        lastDecisionGateTime = System.currentTimeMillis();
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
            if (isDestructive) return true;
        }
        return false;
    }

    private boolean findTextRecursive(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) return true;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(text.toLowerCase())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findTextRecursive(child, text)) return true;
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
