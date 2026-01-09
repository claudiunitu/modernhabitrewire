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
import android.os.Build;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Firewall Live Stats", 
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows real-time budget and session statistics");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void updateStatsNotification() {
        boolean active = appPreferencesManager.getIsBlockerActive();
        long remainingUnits = dopamineBudgetEngine.getRemainingBudget();
        double multiplier = dopamineBudgetEngine.calculateCurrentMultiplier();
        
        long sessionForbiddenUnits = 0;
        if (activeStickyPackage != null) {
            long currentSegmentMs = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
            long totalMs = accumulatedForbiddenTimeMs + currentSegmentMs;
            sessionForbiddenUnits = Math.round((totalMs / 1000.0) * multiplier);
        }

        String status = active ? "Blocker: ACTIVE" : "Blocker: INACTIVE";
        String stats = String.format(Locale.getDefault(), 
                "Potential: %d DU | Session: %d DU | Cost: %.1fx",
                remainingUnits, sessionForbiddenUnits, multiplier);

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

        // 1. SYSTEM & SELF EXCLUSION (Decision Gate / Settings)
        if (packageName.equals(APP_PACKAGE)) {
            // Pause timer while in our app, but DON'T kill the sticky session yet.
            // Forbidden state survives UI detours.
            updateForbiddenTimer(false);
            updateStatsNotification();
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
        if (!appPreferencesManager.getIsBlockerActive()) {
            updateStatsNotification();
            return;
        }

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

        // 5. SESSION WATCHDOG & TERMINATION
        // End session ONLY if the user switches to a DIFFERENT full-screen app or Home
        if (activeStickyPackage != null && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.isFullScreen() && !packageName.equals(activeStickyPackage) && !isTransientSystemOverlay(packageName)) {
                Log.d(TAG, "Watchdog: Ending session for " + activeStickyPackage + " due to switch to " + packageName);
                endStickySession();
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

        updateStatsNotification();
    }

    private void handleAppInterception(String packageName) {
        List<String> extractiveApps = appPreferencesManager.getExtractiveAppsPackages();
        
        boolean isBrowser = false;
        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                isBrowser = true;
                break;
            }
        }

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
        } else if (!isBrowser) {
            // DO NOTHING - App-level events are not safety evidence.
            // This prevents force-resetting forbidden state during transient UI noise (like notification shade).
        }
    }

    private void handleUrlInterception(String packageName) {
        for (SupportedBrowserConfig config : supportedBrowsers) {
            if (packageName.equals(config.packageName)) {
                checkBrowserUrl(config);
                return;
            }
        }
    }

    private void checkBrowserUrl(SupportedBrowserConfig config) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        
        // Guard: If the active window is SystemUI or another overlay, don't process it as browser safety evidence.
        CharSequence rootPackage = root.getPackageName();
        if (rootPackage == null || !rootPackage.toString().equals(config.packageName)) {
            root.recycle();
            return;
        }

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
            String currentUrl = bar.getText().toString().toLowerCase().trim();
            String matchedPattern = null;
            for (String pattern : appPreferencesManager.getForbiddenUrls()) {
                String cleanPattern = pattern.toLowerCase().trim();
                if (!cleanPattern.isEmpty() && currentUrl.contains(cleanPattern)) {
                    matchedPattern = pattern;
                    break;
                }
            }

            if (matchedPattern != null) {
                if (activeStickyPackage != null && config.packageName.equals(activeStickyPackage)) {
                    updateForbiddenTimer(true);
                    if (!sessionApprovedPatterns.contains(matchedPattern)) {
                        appPreferencesManager.setLastInterceptedApp(config.packageName);
                        appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                        triggerDecisionGate();
                    }
                } else if (activeStickyPackage == null) {
                    appPreferencesManager.setLastInterceptedApp(config.packageName);
                    appPreferencesManager.setLastInterceptedUrl(matchedPattern);
                    triggerDecisionGate();
                }
            } else {
                // POSITIVE SAFETY EVIDENCE: Only clear forbidden state if we have a definitive safe URL.
                // We use explicit allow-by-exclusion here: if no forbidden pattern is found in the current URL,
                // and we were previously in a forbidden state, we transition to safe.
                boolean explicitlySafe = true;
                for (String pattern : appPreferencesManager.getForbiddenUrls()) {
                    if (currentUrl.contains(pattern.toLowerCase().trim())) {
                        explicitlySafe = false;
                        break;
                    }
                }

                if (explicitlySafe && isForbiddenConfirmed) {
                    isForbiddenConfirmed = false;
                    updateForbiddenTimer(false);
                }
            }
            bar.recycle();
        }
        root.recycle();
    }

    private void updateForbiddenTimer(boolean isForbidden) {
        if (activeStickyPackage == null) isForbidden = false;

        if (isForbidden) {
            isForbiddenConfirmed = true;
        } else if (isForbiddenConfirmed) {
            // Once forbidden is confirmed, only positive evidence of safety (explicit safe URL) resets it.
            return;
        }

        long now = System.currentTimeMillis();
        if (isForbidden) {
            if (lastForbiddenStartTime == 0) {
                lastForbiddenStartTime = now;
                notificationHandler.post(notificationTicker);
            }
        } else {
            if (lastForbiddenStartTime != 0) {
                accumulatedForbiddenTimeMs += (now - lastForbiddenStartTime);
                lastForbiddenStartTime = 0;
                notificationHandler.removeCallbacks(notificationTicker);
            }
        }
    }

    private void checkLiveBudgetExhaustion() {
        if (activeStickyPackage == null) return;
        
        long remainingUnits = dopamineBudgetEngine.getRemainingBudget();
        long currentForbiddenSegmentMs = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
        long totalForbiddenTimeMs = accumulatedForbiddenTimeMs + currentForbiddenSegmentMs;
        
        double multiplier = dopamineBudgetEngine.calculateCurrentMultiplier();
        long unitCost = Math.round((totalForbiddenTimeMs / 1000.0) * multiplier);

        if (remainingUnits <= 0 || unitCost >= remainingUnits) {
            Log.d(TAG, "POTENTIAL EXHAUSTED. Forcing cleanup and redirect.");
            // Stop everything FIRST to prevent ghost units
            endStickySession();
            performGlobalAction(GLOBAL_ACTION_HOME);
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
        isForbiddenConfirmed = false;
        lastForbiddenStartTime = System.currentTimeMillis();
        notificationHandler.post(notificationTicker);
        dopamineBudgetEngine.incrementSessionCount();
    }

    private void endStickySession() {
        isForbiddenConfirmed = false;
        updateForbiddenTimer(false);
        if (accumulatedForbiddenTimeMs > 0) {
            dopamineBudgetEngine.depleteBudget(accumulatedForbiddenTimeMs);
        }
        activeStickyPackage = null;
        sessionApprovedPatterns.clear();
        accumulatedForbiddenTimeMs = 0;
        lastForbiddenStartTime = 0;
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
               packageName.contains("board") || packageName.contains("ime") || packageName.equals("com.android.settings");
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
