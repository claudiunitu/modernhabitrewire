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
    // MEDIUM-05: Snapshot of remaining budget at session start so mid-session exhaustion
    // is measured against the allowance for this specific approved session, not the
    // cumulative stored value (which may already be negative from prior sessions).
    private long sessionStartBudget = 0;

    // Foreground Ownership Tracking
    private String lastForegroundPackage = null;
    private long lastForegroundChangeTime = 0;
    private static final long FOREGROUND_DEBOUNCE_MS = 300;

    // URL Stability & Cooldown Logic
    private final Map<String, String> lastObservedUrls = new HashMap<>();
    private final Map<String, Long> lastUrlChangeTimes = new HashMap<>();
    private static final long URL_STABLE_MS = 800;

    // Forbidden / Safe hysteresis
    private long lastForbiddenSeenAt = 0;
    private long lastSafeSeenAt = 0;
    private static final long SAFE_CONFIRM_MS = 1500;
    
    // Shared cooldown to prevent browser loops
    private static long lastDecisionGateTime = 0;
    private static final long DECISION_COOLDOWN_MS = 5000;

    // Grace period to protect an approved launch from being cancelled by transient overlays
    private static long tempAllowGrantedAt = 0;
    private static final long TEMP_ALLOW_GRACE_MS = 4000;

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

    public static void notifyGateClosed() {
        lastDecisionGateTime = System.currentTimeMillis();
        Log.d(TAG, "Gate closure notified. Cooldown reset.");
    }

    public static void notifyTempAllowGranted() {
        tempAllowGrantedAt = System.currentTimeMillis();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        // CRITICAL-01: Clear any stale temp-allow flag that survived a process death so a
        // previous gate approval can never silently bypass enforcement after restart.
        appPreferencesManager.setTempAllowAppLaunch(false);
        dopamineBudgetEngine = new DopamineBudgetEngine(this);
        this.supportedBrowsers = getSupportedBrowsers();
        
        refreshImeList();
        refreshLauncherList();
        createNotificationChannel();
        updateStatsNotification();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        
        dopamineBudgetEngine.checkDecayResponsive();
        updateStatsNotification();
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
        if (dopamineBudgetEngine != null) {
            dopamineBudgetEngine.resetBudgetIfNeeded();
        }
        
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

        int iconRes;
        if (!active) {
            iconRes = android.R.drawable.ic_lock_power_off;
        } else if (isForbiddenConfirmed) {
            iconRes = android.R.drawable.ic_dialog_alert;
        } else {
            iconRes = android.R.drawable.ic_dialog_info;
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(status)
                .setContentText(stats)
                .setSmallIcon(iconRes)
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

    // Current window class tracking for danger-zone detection
    private String lastWindowClassName = "";

    // Notification throttle: avoid heavy I/O on every accessibility event
    private long lastNotificationUpdateTime = 0;
    private static final long NOTIFICATION_THROTTLE_MS = 1000;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Guard: managers are initialised in onServiceConnected; ignore any events
        // that arrive before that callback completes.
        if (appPreferencesManager == null || dopamineBudgetEngine == null) return;
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        int eventType = event.getEventType();

        if (packageName.equals(APP_PACKAGE)) {
            updateStatsNotification();
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null) {
            lastWindowClassName = event.getClassName().toString();
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
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Grace period: don't cancel an approved launch in the first TEMP_ALLOW_GRACE_MS
                // after the flag was granted. This prevents brief system overlays (e.g. Google
                // Play Services dialogs, permission prompts) from killing the launch before
                // the target app has had a chance to appear in the foreground.
                if (System.currentTimeMillis() - tempAllowGrantedAt > TEMP_ALLOW_GRACE_MS) {
                    appPreferencesManager.setTempAllowAppLaunch(false);
                }
            }
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
            
            if (!isExtractive) {
                dopamineBudgetEngine.checkDecayResponsive();
                updateStatsNotification();
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleUrlInterception(packageName, eventType);
        }

        if (activeStickyPackage != null) {
            checkLiveBudgetExhaustion();
        }

        // Throttle notification updates: the 1-second ticker handles updates while
        // forbidden time is actively running; outside of that, cap at once per second
        // to avoid SharedPreferences reads + NotificationManager.notify() on every scroll.
        long now2 = System.currentTimeMillis();
        if (lastForbiddenStartTime == 0 && now2 - lastNotificationUpdateTime >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateTime = now2;
            updateStatsNotification();
        }
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
            if (dopamineBudgetEngine.getRemainingBudget() <= 0) {
                performGlobalAction(GLOBAL_ACTION_HOME);
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

        if (root.getPackageName() == null ||
            !root.getPackageName().toString().equals(config.packageName)) {
            root.recycle();
            return;
        }

        AccessibilityNodeInfo bar = null;
        for (String id : config.addressBarIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                bar = nodes.get(0);
                for (int i = 1; i < nodes.size(); i++) nodes.get(i).recycle();
                break;
            }
        }
        if (bar == null) bar = findUrlBarByContentDescription(root);

        long now = System.currentTimeMillis();

        // -----------------------------
        // 1. URL missing → uncertainty
        // -----------------------------
        if (bar == null || bar.getText() == null) {
            if (bar != null) bar.recycle();
            root.recycle();
            return;
        }

        String currentUrl = bar.getText().toString().toLowerCase().trim();

        // -----------------------------
        // 2. URL stability filter
        // -----------------------------
        String prev = lastObservedUrls.get(config.packageName);
        if (!currentUrl.equals(prev)) {
            lastObservedUrls.put(config.packageName, currentUrl);
            lastUrlChangeTimes.put(config.packageName, now);
            // On window state changes (browser restored/tab switch), the URL is already
            // committed — don't skip; fall through to the forbidden check below.
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                bar.recycle();
                root.recycle();
                return;
            }
        }

        long lastChange = lastUrlChangeTimes.getOrDefault(config.packageName, 0L);
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && now - lastChange < URL_STABLE_MS) {
            bar.recycle();
            root.recycle();
            return;
        }

        boolean committed = !bar.isFocused() || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

        // -----------------------------
        // 3. Forbidden match
        // -----------------------------
        String matchedPattern = null;
        for (String pattern : appPreferencesManager.getForbiddenUrls()) {
            String p = pattern.toLowerCase().trim();
            if (!p.isEmpty() && currentUrl.contains(p)) {
                matchedPattern = pattern;
                break;
            }
        }

        if (matchedPattern != null && committed) {
            lastForbiddenSeenAt = now;
            lastSafeSeenAt = 0;

            if (System.currentTimeMillis() - lastDecisionGateTime < DECISION_COOLDOWN_MS) {
                bar.recycle();
                root.recycle();
                return;
            }

            if (dopamineBudgetEngine.getRemainingBudget() <= 0) {
                bar.recycle();
                root.recycle();
                performGlobalAction(GLOBAL_ACTION_BACK);
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

            bar.recycle();
            root.recycle();
            return;
        }

        // -----------------------------
        // 4. Explicit SAFE detection
        // -----------------------------
        if (committed && activeStickyPackage != null && config.packageName.equals(activeStickyPackage)) {
            if (isKnownSafeNewTab(currentUrl)) {
                confirmSafeState(config.packageName);
            } else {
                if (lastSafeSeenAt == 0) lastSafeSeenAt = now;

                if (isForbiddenConfirmed && (now - lastSafeSeenAt > SAFE_CONFIRM_MS)) {
                    confirmSafeState(config.packageName);
                }
            }
        } else {
            lastSafeSeenAt = 0;
        }

        bar.recycle();
        root.recycle();
    }

    private boolean isKnownSafeNewTab(String url) {
        if (url == null) return true;
        String u = url.toLowerCase();
        return u.equals("about:blank") ||
               u.contains("newtab") ||
               u.contains("chrome://newtab") ||
               u.contains("brave://newtab") ||
               u.contains("about:home");
    }

    private void confirmSafeState(String packageName) {
        if (!isForbiddenConfirmed) return;
        isForbiddenConfirmed = false;
        lastForbiddenSeenAt = 0;
        lastSafeSeenAt = 0;
        updateForbiddenTimer(false);
    }

    private void updateForbiddenTimer(boolean isForbidden) {
        if (activeStickyPackage == null) isForbidden = false;
        long now = System.currentTimeMillis();

        if (isForbidden) {
            if (!isForbiddenConfirmed) {
                isForbiddenConfirmed = true;
            }
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
            isForbiddenConfirmed = false;
        }
    }

    private void checkLiveBudgetExhaustion() {
        if (activeStickyPackage == null || isBudgetLockedOut) return;
        if (sessionStartBudget <= 0) return;

        long currentForbiddenSegmentMs = (lastForbiddenStartTime == 0) ? 0 : (System.currentTimeMillis() - lastForbiddenStartTime);
        long totalForbiddenTimeMs = accumulatedForbiddenTimeMs + currentForbiddenSegmentMs;
        long unitCost = dopamineBudgetEngine.calculateEscalatedCost(totalForbiddenTimeMs);

        if (unitCost >= sessionStartBudget) {
            isBudgetLockedOut = true;
            endStickySession();
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void startStickySession(String packageName) {
        isBudgetLockedOut = false;
        sessionStartBudget = dopamineBudgetEngine.getRemainingBudget();

        activeStickyPackage = packageName;
        sessionApprovedPatterns.clear();
        String interceptedUrl = appPreferencesManager.getLastInterceptedUrl();
        if (!interceptedUrl.isEmpty()) sessionApprovedPatterns.add(interceptedUrl);

        accumulatedForbiddenTimeMs = 0;
        isForbiddenConfirmed = false;
        lastForbiddenStartTime = 0;

        dopamineBudgetEngine.incrementSessionCount();

        // For non-browser apps the entire session is forbidden time, so start the timer
        // immediately. Browser sessions let checkBrowserUrl() start/stop the timer based
        // on whether a forbidden URL pattern is currently committed in the address bar.
        if (!isBrowserPackage(packageName)) {
            updateForbiddenTimer(true);
        }
    }

    private boolean isBrowserPackage(String packageName) {
        if (supportedBrowsers == null || packageName == null) return false;
        for (SupportedBrowserConfig browser : supportedBrowsers) {
            if (packageName.equals(browser.packageName)) return true;
        }
        return false;
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
        isForbiddenConfirmed = false;
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

        if (activeStickyPackage != null) {
            endStickySession();
        }

        // Reset foreground tracking so the forbidden app is always re-detected when
        // it returns to foreground, regardless of how the gate was dismissed (Cancel
        // button, hardware back, or system navigation). Without this, the gate never
        // re-fires if lastForegroundPackage still equals the forbidden app's package.
        lastForegroundPackage = null;

        Intent intent = new Intent(this, DecisionGateActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    // Class-name substrings that identify app-info / package-installer screens —
    // the only screens that show actionable "Uninstall" and "Force Stop" buttons for our app.
    private static final List<String> APP_INFO_CLASS_FRAGMENTS = Arrays.asList(
            "AppInfoDashboard", "InstalledAppDetails", "AppInfoBase",
            "ManageApplications", "AppStorageSettings", "AppNotificationSettings",
            "UninstallerActivity", "PackageInstallerActivity", "InstallAppProgress",
            "UninstallAppProgress", "DeletePackage"
    );

    private boolean isDangerZoneActive() {
        // Use the window class name to determine whether we are on a genuine
        // app-info or uninstaller screen — NOT an accessibility settings screen.
        // Accessibility screens have class names containing "accessibility" or "Accessibility".
        String cls = lastWindowClassName.toLowerCase();
        if (cls.contains("accessibility")) return false;

        // Must be on a known app-info / package-installer screen.
        boolean onAppInfoScreen = false;
        for (String fragment : APP_INFO_CLASS_FRAGMENTS) {
            if (lastWindowClassName.contains(fragment)) {
                onAppInfoScreen = true;
                break;
            }
        }
        if (!onAppInfoScreen) return false;

        // Confirm the screen is actually about our app by scanning text.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean foundOurApp = findTextRecursive(root, APP_PACKAGE)
                || findTextRecursive(root, APP_NAME);
        root.recycle();
        return foundOurApp;
    }

    private boolean findTextRecursive(AccessibilityNodeInfo node, String text) {
        return findTextRecursiveInternal(node, text.toLowerCase(), 0);
    }

    // MEDIUM-03: Depth-limited to prevent StackOverflowError on deep accessibility trees.
    private boolean findTextRecursiveInternal(AccessibilityNodeInfo node, String lowerText, int depth) {
        if (node == null || depth > 30) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(lowerText)) return true;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(lowerText)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findTextRecursiveInternal(child, lowerText, depth + 1)) {
                if (child != null) child.recycle();
                return true;
            }
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
                if (d.contains(hint)) return AccessibilityNodeInfo.obtain(node);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeByContentDescription(child, hints);
            if (child != null) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        super.onDestroy();
    }

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
