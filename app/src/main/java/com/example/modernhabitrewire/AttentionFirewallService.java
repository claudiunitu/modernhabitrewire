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
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

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
    private long forbiddenConfirmedAt = 0;
    
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

    // Overlay Friction State
    private boolean isFrictionRunning = false;
    private View frictionOverlay = null;
    private WindowManager windowManager;
    private final Handler frictionOverlayHandler = new Handler(Looper.getMainLooper());
    private static final long FRICTION_MIN_INTERVAL_MS = 5000;
    private long lastOverlayTime = 0;

    private void applyOverlayFriction() {
        long now = System.currentTimeMillis();
        if (now - lastOverlayTime < FRICTION_MIN_INTERVAL_MS) return;

        // Intensity ramps over 60 seconds of consumption
        long forbiddenSeconds = (now - forbiddenConfirmedAt) / 1000;
        double intensity = Math.min(1.0, (double) forbiddenSeconds / 60.0);

        // Probability increases with intensity
        if (Math.random() > (0.1 + intensity * 0.4)) return;

        lastOverlayTime = now;
        showFrictionOverlay(intensity);
    }

    private void showFrictionOverlay(double intensity) {
        frictionOverlayHandler.post(() -> {
            if (frictionOverlay != null) return;

            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            frictionOverlay = inflater.inflate(R.layout.friction_overlay, null);

            // Always use hard occlusion (intercept touches) for reliability
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            // Determine if we should perform a HOME action after this overlay
            // Probability scales with intensity, up to 25% at max intensity.
            final boolean shouldKickHome = Math.random() < (intensity * 0.25);
            TextView msg = frictionOverlay.findViewById(R.id.friction_message);

            if (shouldKickHome) {
                if (msg != null) {
                    msg.setText(getString(R.string.friction_kick_home_message));
                    msg.setVisibility(View.VISIBLE);
                }
            } else {
                // Occasionally show a generic message
                if (Math.random() < 0.5) {
                    if (msg != null) {
                        int[] hintResIds = {
                                R.string.friction_hint_pause,
                                R.string.friction_hint_enough,
                                R.string.friction_hint_tired,
                                R.string.friction_hint_breathe
                        };
                        msg.setText(getString(hintResIds[(int)(Math.random() * hintResIds.length)]));
                        msg.setVisibility(View.VISIBLE);
                    }
                }
            }

            try {
                windowManager.addView(frictionOverlay, params);
                
                // Randomize duration between 1s and 10s based on intensity
                long duration = 1000 + (long) (Math.random() * intensity * 9000);
                
                frictionOverlayHandler.postDelayed(() -> {
                    hideFrictionOverlay();
                    if (shouldKickHome && activeStickyPackage != null) {
                        Log.d(TAG, "Friction: Kicking to HOME");
                        performGlobalAction(GLOBAL_ACTION_HOME);
                    }
                }, duration);
            } catch (Exception e) {
                Log.e(TAG, "Failed to add friction overlay", e);
                frictionOverlay = null;
            }
        });
    }

    private void hideFrictionOverlay() {
        if (frictionOverlay != null) {
            try {
                windowManager.removeView(frictionOverlay);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove friction overlay", e);
            }
            frictionOverlay = null;
        }
    }

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
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | 
                         AccessibilityEvent.TYPE_VIEW_SCROLLED;
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

        // Apply stochastic occlusion friction
        if (isFrictionRunning && isForbiddenConfirmed) {
            if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || 
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                applyOverlayFriction();
            }
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
            if (!isForbiddenConfirmed) {
                isForbiddenConfirmed = true;
                forbiddenConfirmedAt = now;
            }
            if (lastForbiddenStartTime == 0) {
                lastForbiddenStartTime = now;
                notificationHandler.post(notificationTicker);
            }
            
            if (dopamineBudgetEngine.getRemainingBudget() <= 0 && !isFrictionRunning) {
                isFrictionRunning = true;
            }
        } else {
            if (lastForbiddenStartTime != 0) {
                accumulatedForbiddenTimeMs += (now - lastForbiddenStartTime);
                lastForbiddenStartTime = 0;
                notificationHandler.removeCallbacks(notificationTicker);
            }
            
            // Invalidate friction when exiting forbidden state
            isFrictionRunning = false;
            isForbiddenConfirmed = false;
            forbiddenConfirmedAt = 0;
            hideFrictionOverlay();
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
        forbiddenConfirmedAt = 0;
        lastForbiddenStartTime = 0; 
        
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
        
        // Final invalidation
        isFrictionRunning = false;
        isForbiddenConfirmed = false;
        forbiddenConfirmedAt = 0;
        hideFrictionOverlay();
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
        
        // Safety: If we are in a sticky session, we MUST end it now so the Gate
        // foreground state doesn't inherit any friction or sticky tracking.
        if (activeStickyPackage != null) {
            endStickySession();
        }

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

    @Override public void onInterrupt() {
        hideFrictionOverlay();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        hideFrictionOverlay();
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
