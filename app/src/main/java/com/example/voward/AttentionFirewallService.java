package com.example.voward;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

public class AttentionFirewallService extends AccessibilityService {

    private static final String TAG = "AttentionFirewall";
    private static final String APP_NAME = "Voward";
    private static final String APP_PACKAGE = "com.example.voward";
    private static final String CHANNEL_ID = "firewall_stats_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private static final List<String> DANGER_PACKAGES = Arrays.asList(
            "com.android.settings", "com.android.packageinstaller", 
            "com.google.android.packageinstaller", "com.android.vending"
    );

    private AppPreferencesManagerSingleton appPreferencesManager;
    private AttentionBudgetEngine attentionBudgetEngine;
    private GrayscaleController grayscaleController;
    
    // Sticky Session State
    private String activeStickyPackage = null;
    
    // Usage tracking: accumulate only time spent in the restricted target.
    private final Set<String> sessionApprovedPatterns = new HashSet<>();
    private long accumulatedRestrictedTimeMs = 0;
    // Monotonic timestamp; wall-clock changes must not change session duration.
    private long restrictedSegmentStartedAt = 0;
    private long persistedRestrictedTimeMs = 0;
    private long persistedUsageSeconds = 0;
    private boolean restrictedUseConfirmed = false;
    private long lastCheckpointAt = 0;
    private long lastActiveNotificationAt = 0;
    private static final long CHECKPOINT_INTERVAL_MS = 5000;

    // Forced Cleanup / Lockout Logic
    private boolean isBudgetLockedOut = false;
    // MEDIUM-05: Snapshot of remaining budget at session start so mid-session exhaustion
    // is measured against the allowance for this specific approved session, not the
    // cumulative stored value (which may already be negative from prior sessions).
    private long sessionLimitSeconds = 0;
    private boolean sessionLimitReached = false;

    // Foreground Ownership Tracking
    private String lastForegroundPackage = null;
    private long lastForegroundChangeTime = 0;
    private static final long FOREGROUND_DEBOUNCE_MS = 300;

    // URL Stability & Cooldown Logic
    private final Map<String, String> lastObservedUrls = new HashMap<>();
    private final Map<String, Long> lastUrlChangeTimes = new HashMap<>();
    private static final long URL_STABLE_MS = 800;

    // Deferred URL checks: fire after stability window even if no further events arrive
    private final Handler urlCheckHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pendingUrlChecks = new HashMap<>();
    private final Map<String, Integer> browserRedirectAttempts = new HashMap<>();
    private static final int MAX_BROWSER_REDIRECT_ATTEMPTS = 4;

    // Pending browser URL clear: set when the gate is cancelled for a browser interception.
    // Fired the next time that browser package comes to the foreground, rather than on a
    // fixed timer, so it works regardless of whether the user pressed Home before Cancel.
    private BrowserSupport.Config pendingBrowserAddressClear = null;

    // Restricted/safe state hysteresis
    private long lastRestrictedSeenAt = 0;
    private long lastSafeSeenAt = 0;
    private static final long SAFE_CONFIRM_MS = 1500;
    
    // Shared cooldown to prevent browser loops
    private static long lastDecisionGateTime = 0;
    private static final long DECISION_COOLDOWN_MS = 5000;

    // Stabilization interval so transient overlays do not cancel an approved launch.
    private static long tempAllowGrantedAt = 0;
    private static final long TEMP_ALLOW_STABILIZATION_MS = 4000;

    private final Set<String> installedImePackages = new HashSet<>();
    private final Set<String> launcherPackages = new HashSet<>();
    private List<BrowserSupport.Config> supportedBrowsers;
    private final Map<String, BrowserSupport.Config> supportedBrowserByPackage = new HashMap<>();
    private StaticBlockPageServer blockPageServer;

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private final Runnable notificationTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            checkpointSessionUsage(false);
            checkLiveBudgetExhaustion();
            long now = SystemClock.elapsedRealtime();
            if (now - lastActiveNotificationAt >= CHECKPOINT_INTERVAL_MS) {
                lastActiveNotificationAt = now;
                updateStatsNotification();
            }
            if (restrictedSegmentStartedAt != 0 && activeStickyPackage != null) {
                notificationHandler.postDelayed(this, 1000);
            }
        }
    };
    private boolean destroyed = false;

    public static void notifyGateClosed() {
        Log.d(TAG, "Gate closed (cancelled).");
    }

    public static void notifyTempAllowGranted() {
        tempAllowGrantedAt = SystemClock.elapsedRealtime();
    }

    public static void notifyGateCancelled() {
        if (instance != null) {
            instance.handleGateCancelled();
        }
    }

    private void handleGateCancelled() {

        // For browser URL interceptions: don't go back in history. Instead, wait for
        // the gate's finish() to complete and Chrome to return to the foreground, then
        // navigate to the local block page so the restricted URL is gone.
        String interceptedApp = appPreferencesManager.getLastInterceptedApp();
        if ("URL".equals(appPreferencesManager.getLastInterceptionKind())
                && interceptedApp != null && !interceptedApp.isEmpty() && supportedBrowsers != null) {
            for (BrowserSupport.Config config : supportedBrowsers) {
                if (interceptedApp.equals(config.packageName)) {
                    // Keep the browser usable: replace the blocked page instead of sending
                    // the entire browser to Home. This also gives the user access to the
                    // tab switcher if browser-specific navigation is unavailable.
                    urlCheckHandler.postDelayed(() -> beginBrowserRedirect(config), 500);
                    return;
                }
            }
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private static AttentionFirewallService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        // CRITICAL-01: Clear any stale temp-allow flag that survived a process death so a
        // previous gate approval can never silently bypass enforcement after restart.
        appPreferencesManager.setTempAllowAppLaunch(false);
        attentionBudgetEngine = new AttentionBudgetEngine(this);
        grayscaleController = new GrayscaleController(this);
        blockPageServer = new StaticBlockPageServer();
        String blockPageAddress = blockPageServer.start();
        this.supportedBrowsers = getSupportedBrowsers(blockPageAddress);
        supportedBrowserByPackage.clear();
        for (BrowserSupport.Config browser : supportedBrowsers) {
            supportedBrowserByPackage.put(browser.packageName, browser);
        }
        
        refreshImeList();
        refreshLauncherList();
        createNotificationChannel();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 100; // Coalesce bursts of scroll/content-change events.
        setServiceInfo(info);
        
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
        if (attentionBudgetEngine != null) {
            attentionBudgetEngine.resetBudgetIfNeeded();
        }
        
        boolean active = appPreferencesManager.getIsBlockerActive();
        lastNotifiedActive = active;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        long remainingSeconds = attentionBudgetEngine.getRemainingBudget();
        
        long sessionUsageSeconds = 0;
        
        if (activeStickyPackage != null) {
            long currentSegmentMs = (restrictedSegmentStartedAt == 0) ? 0
                    : (SystemClock.elapsedRealtime() - restrictedSegmentStartedAt);
            long totalMs = accumulatedRestrictedTimeMs + currentSegmentMs;
            
            sessionUsageSeconds = attentionBudgetEngine.calculateUsageSeconds(totalMs);
        }

        String status = active ? getString(R.string.blocker_active) : getString(R.string.blocker_inactive);
        String stats = getString(R.string.notification_stats_template,
                formatMinutesSeconds(remainingSeconds), formatMinutesSeconds(sessionUsageSeconds));

        Intent intent = new Intent(this, ModernMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(status)
                .setContentText(stats)
                // Android tints notification icons from their alpha channel. This wrapper
                // only frames the canonical artwork more tightly; it does not redraw it.
                .setSmallIcon(R.drawable.ic_notification_voward)
                .setColor(getColor(R.color.md_primary_container))
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .build();

        manager.notify(NOTIFICATION_ID, notification);
    }

    private static String formatMinutesSeconds(long seconds) {
        boolean negative = seconds < 0;
        long safe = seconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(seconds);
        String value = String.format(Locale.getDefault(), "%d:%02d", safe / 60, safe % 60);
        return negative ? "-" + value : value;
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
    private Boolean lastNotifiedActive = null;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Guard: managers are initialised in onServiceConnected; ignore any events
        // that arrive before that callback completes.
        if (appPreferencesManager == null || attentionBudgetEngine == null) return;
        if (event.getPackageName() == null) return;
        String packageName = event.getPackageName().toString();
        int eventType = event.getEventType();
        long eventTime = SystemClock.elapsedRealtime();

        if (packageName.equals(APP_PACKAGE)) {
            boolean activeNow = appPreferencesManager.getIsBlockerActive();
            if (lastNotifiedActive == null || activeNow != lastNotifiedActive
                    || eventTime - lastNotificationUpdateTime >= NOTIFICATION_THROTTLE_MS) {
                lastNotificationUpdateTime = eventTime;
                updateStatsNotification();
            }
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null) {
            lastWindowClassName = event.getClassName().toString();
        }

        if (appPreferencesManager.getIsBlockerActive()
                && appPreferencesManager.isUninstallGuardEnabled()
                && DANGER_PACKAGES.contains(packageName)) {
            if (isDangerZoneActive()) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
        }

        if (!appPreferencesManager.getIsBlockerActive()) {
            return;
        }

        long now = eventTime;
        boolean isNewForeground = lastForegroundPackage == null || !packageName.equals(lastForegroundPackage);

        if (isNewForeground && (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || now - lastForegroundChangeTime > FOREGROUND_DEBOUNCE_MS)) {
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
                // Do not cancel an approved launch during the stabilization interval.
                // after the flag was granted. This prevents brief system overlays (e.g. Google
                // Play Services dialogs, permission prompts) from killing the launch before
                // the target app has had a chance to appear in the foreground.
                if (SystemClock.elapsedRealtime() - tempAllowGrantedAt > TEMP_ALLOW_STABILIZATION_MS) {
                    appPreferencesManager.setTempAllowAppLaunch(false);
                }
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean isTransient = isTransientSystemOverlay(packageName);
            boolean isLauncher = isLauncherPackage(packageName);
            boolean isRestricted = appPreferencesManager.isRestrictedApp(packageName);
            
            if (activeStickyPackage != null) {
                if (!packageName.equals(activeStickyPackage) && !isTransient) {
                    endStickySession();
                    if (!isRestricted && !isLauncher) isBudgetLockedOut = false;
                }
            } else if (!isTransient && !isLauncher && !isRestricted) {
                isBudgetLockedOut = false;
            }
            
            if (!isRestricted) updateStatsNotification();
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleUrlInterception(packageName, eventType);
        }

        if (activeStickyPackage != null) {
            checkpointSessionUsage(false);
            checkLiveBudgetExhaustion();
        }

        // Throttle notification updates: the 1-second ticker handles updates while
        // restricted time is actively running; outside of that, cap at once per second
        // to avoid SharedPreferences reads + NotificationManager.notify() on every scroll.
        long now2 = SystemClock.elapsedRealtime();
        if (restrictedSegmentStartedAt == 0
                && now2 - lastNotificationUpdateTime >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateTime = now2;
            updateStatsNotification();
        }
    }

    private void onForegroundAppChanged(String packageName) {
        if (isTransientSystemOverlay(packageName) || isLauncherPackage(packageName)) return;

        // Fire any pending browser address-bar clear now that the browser is actually
        // in the foreground. Refresh the cooldown so the URL check that runs immediately
        // after this does not re-gate before the clear has a chance to complete.
        if (pendingBrowserAddressClear != null
                && packageName.equals(pendingBrowserAddressClear.packageName)) {
            final BrowserSupport.Config config = pendingBrowserAddressClear;
            pendingBrowserAddressClear = null;
            lastDecisionGateTime = SystemClock.elapsedRealtime();
            // Small delay so the window is fully rendered before touching the URL bar.
            urlCheckHandler.postDelayed(() -> {
                String observed = lastObservedUrls.get(config.packageName);
                if (observed != null) {
                    boolean stillRestricted = false;
                    stillRestricted = appPreferencesManager.findRestrictedUrlPattern(observed) != null;
                    if (stillRestricted) beginBrowserRedirect(config);
                }
            }, 1000);
        }

        handleAppInterception(packageName);
    }

    private void handleAppInterception(String packageName) {
        if (appPreferencesManager.isRestrictedApp(packageName)
                && !SafetyPolicy.isCriticalPackage(packageName, APP_PACKAGE)) {
            appPreferencesManager.setLastInterceptedApp(packageName);
            appPreferencesManager.setLastInterceptedUrl("");
            appPreferencesManager.setLastInterceptionKind("APP");
            if (appPreferencesManager.isStrictRestrictedApp(packageName)) {
                triggerDecisionGate(true);
                return;
            }
            if (activeStickyPackage != null && packageName.equals(activeStickyPackage)) {
                updateRestrictedTimer(true);
                return;
            }
            attentionBudgetEngine.resetBudgetIfNeeded();
            if (attentionBudgetEngine.getRemainingBudget() <= 0) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
            triggerDecisionGate();
        }
    }

    private void handleUrlInterception(String packageName, int eventType) {
        BrowserSupport.Config config = findBrowserConfig(packageName);
        // Strict URL rules must still be detected inside an otherwise approved whole-browser
        // session. enforceCommittedRestrictedUrl keeps ordinary URL rules bypassed there.
        if (config != null) checkBrowserUrl(config, eventType);
    }

    private void checkBrowserUrl(BrowserSupport.Config config, int eventType) {
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

        long now = SystemClock.elapsedRealtime();

        // -----------------------------
        // 1. URL missing → uncertainty
        // -----------------------------
        if (bar == null || bar.getText() == null) {
            if (bar != null) bar.recycle();
            root.recycle();
            return;
        }

        String currentUrl = bar.getText().toString().toLowerCase(Locale.ROOT).trim();

        // The local block page is always safe, even if the user has added a broad
        // loopback/localhost pattern to the restricted list. Browsers may hide the scheme.
        if (BrowserSupport.isConfiguredSafeAddress(config, currentUrl)) {
            lastObservedUrls.put(config.packageName, currentUrl);
            confirmSafeState(config.packageName);
            bar.recycle();
            root.recycle();
            return;
        }

        // -----------------------------
        // 2. URL stability filter
        // -----------------------------
        String prev = lastObservedUrls.get(config.packageName);
        if (!currentUrl.equals(prev)) {
            lastObservedUrls.put(config.packageName, currentUrl);
            lastUrlChangeTimes.put(config.packageName, now);
            // On window state changes (browser restored/tab switch), the URL is already
            // committed — don't skip; fall through to the restricted check below.
            if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Schedule a deferred check so it fires even if no further events arrive
                scheduleDeferredUrlCheck(config, currentUrl);
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

        // Bar focused = user is editing. Autocomplete may fill a complete restricted URL,
        // but it is not a committed navigation until the browser releases input focus.
        boolean committed = !bar.isFocused();

        // -----------------------------
        // 3. Restricted match
        // -----------------------------
        BrowserUrlEnforcementPolicy.RuleMatch matched =
                BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                currentUrl, currentUrl, bar.isFocused(),
                appPreferencesManager.getStrictRestrictedUrlsSnapshot(),
                appPreferencesManager.getRestrictedUrlsSnapshot());

        if (matched != null) {
            enforceCommittedRestrictedUrl(
                    config, bar, matched.pattern, matched.strict, now);
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

                if (restrictedUseConfirmed && (now - lastSafeSeenAt > SAFE_CONFIRM_MS)) {
                    confirmSafeState(config.packageName);
                }
            }
        } else {
            lastSafeSeenAt = 0;
        }

        bar.recycle();
        root.recycle();
    }

    private void scheduleDeferredUrlCheck(BrowserSupport.Config config, String url) {
        // Cancel any previously scheduled check for this browser (URL may have changed again)
        Runnable existing = pendingUrlChecks.get(config.packageName);
        if (existing != null) urlCheckHandler.removeCallbacks(existing);

        Runnable check = () -> {
            pendingUrlChecks.remove(config.packageName);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null
                    || !config.packageName.equals(root.getPackageName().toString())) {
                if (root != null) root.recycle();
                return;
            }

            AccessibilityNodeInfo bar = findAddressBar(root, config);
            if (bar == null || bar.getText() == null) {
                if (bar != null) bar.recycle();
                root.recycle();
                return;
            }

            String visibleUrl = bar.getText().toString();
            BrowserUrlEnforcementPolicy.RuleMatch match =
                    BrowserUrlEnforcementPolicy.findCommittedRestrictedMatch(
                    url, visibleUrl, bar.isFocused(),
                    appPreferencesManager.getStrictRestrictedUrlsSnapshot(),
                    appPreferencesManager.getRestrictedUrlsSnapshot());
            if (match != null) {
                lastObservedUrls.put(config.packageName,
                        visibleUrl.toLowerCase(Locale.ROOT).trim());
                enforceCommittedRestrictedUrl(
                        config, bar, match.pattern, match.strict,
                        SystemClock.elapsedRealtime());
            }
            bar.recycle();
            root.recycle();
        };
        pendingUrlChecks.put(config.packageName, check);
        urlCheckHandler.postDelayed(check, URL_STABLE_MS);
    }

    private void enforceCommittedRestrictedUrl(
            BrowserSupport.Config config,
            AccessibilityNodeInfo bar,
            String matchedPattern,
            boolean strict,
            long now) {
        lastRestrictedSeenAt = now;
        lastSafeSeenAt = 0;

        boolean approvedWholeBrowser = InterceptionPolicy.isApprovedWholeBrowserSession(
                appPreferencesManager.getLastInterceptionKind(),
                config.packageName.equals(activeStickyPackage), true);
        if (approvedWholeBrowser && !strict) return;

        // Prevent a second action while a gate is closing or an in-place redirect is
        // changing the omnibox.
        if (SystemClock.elapsedRealtime() - lastDecisionGateTime < DECISION_COOLDOWN_MS) return;

        if (strict) {
            rememberBrowserInterception(config, matchedPattern);
            triggerDecisionGate(true);
            return;
        }

        attentionBudgetEngine.resetBudgetIfNeeded();
        if (attentionBudgetEngine.getRemainingBudget() <= 0) {
            rememberBrowserInterception(config, matchedPattern);
            beginBrowserRedirect(config, bar);
            lastDecisionGateTime = SystemClock.elapsedRealtime();
            showSessionCompleteRedirectMessage();
            return;
        }

        if (activeStickyPackage != null && config.packageName.equals(activeStickyPackage)) {
            updateRestrictedTimer(true);
            if (!sessionApprovedPatterns.contains(matchedPattern)) {
                rememberBrowserInterception(config, matchedPattern);
                triggerDecisionGate();
            }
        } else if (activeStickyPackage == null) {
            rememberBrowserInterception(config, matchedPattern);
            triggerDecisionGate();
        }
    }

    /**
     * Persists the browser context before opening any gate. DecisionGateActivity lives in
     * a separate task, so the accessibility service may receive its close callback only
     * after the browser has stopped producing events. Without this handoff the cancel path
     * cannot distinguish a blocked URL from a blocked app and incorrectly goes Home.
     */
    private void rememberBrowserInterception(BrowserSupport.Config config, String pattern) {
        appPreferencesManager.setLastInterceptedApp(config.packageName);
        appPreferencesManager.setLastInterceptedUrl(pattern);
        appPreferencesManager.setLastInterceptionKind("URL");
    }

    /**
     * Replaces the address in the existing browser tab. Deliberately does not use an
     * ACTION_VIEW intent (which may open another tab) or a global Home/Back action (which
     * may minimize the browser while leaving the restricted page in place).
     */
    private void beginBrowserRedirect(BrowserSupport.Config config) {
        browserRedirectAttempts.put(config.packageName, 0);
        redirectCurrentBrowserTab(config);
    }

    private void beginBrowserRedirect(
            BrowserSupport.Config config, AccessibilityNodeInfo currentBar) {
        browserRedirectAttempts.put(config.packageName, 0);
        if (!redirectAddressBarInPlace(config, currentBar)) {
            scheduleBrowserRedirectRetry(config);
        }
    }

    private void redirectCurrentBrowserTab(BrowserSupport.Config config) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !config.packageName.equals(root.getPackageName().toString())) {
            if (root != null) root.recycle();
            pendingBrowserAddressClear = config;
            return;
        }

        AccessibilityNodeInfo bar = findAddressBar(root, config);
        if (bar == null) {
            root.recycle();
            pendingBrowserAddressClear = config;
            Log.w(TAG, "Could not find the active browser address bar for in-place redirect");
            return;
        }

        boolean redirected = redirectAddressBarInPlace(config, bar);
        bar.recycle();
        root.recycle();
        if (redirected) {
            browserRedirectAttempts.remove(config.packageName);
            if (pendingBrowserAddressClear == config) pendingBrowserAddressClear = null;
        } else {
            scheduleBrowserRedirectRetry(config);
        }
    }

    private void scheduleBrowserRedirectRetry(BrowserSupport.Config config) {
        int attempts = browserRedirectAttempts.getOrDefault(config.packageName, 0);
        if (attempts >= MAX_BROWSER_REDIRECT_ATTEMPTS) {
            browserRedirectAttempts.remove(config.packageName);
            pendingBrowserAddressClear = config;
            Log.w(TAG, "In-place redirect is waiting for an editable browser address bar");
            return;
        }
        browserRedirectAttempts.put(config.packageName, attempts + 1);
        urlCheckHandler.postDelayed(() -> redirectCurrentBrowserTab(config), 250);
    }

    private AccessibilityNodeInfo findAddressBar(
            AccessibilityNodeInfo root, BrowserSupport.Config config) {
        AccessibilityNodeInfo fallback = null;
        for (String id : config.addressBarIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null) continue;
            AccessibilityNodeInfo editable = null;
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isEditable()) {
                    editable = node;
                    break;
                }
            }
            if (editable != null) {
                if (fallback != null) fallback.recycle();
                // Recycle every returned node except the editable result.
                for (AccessibilityNodeInfo other : nodes) {
                    if (other != editable) other.recycle();
                }
                return editable;
            }
            for (AccessibilityNodeInfo node : nodes) {
                if (fallback == null) fallback = node;
                else node.recycle();
            }
        }
        if (fallback != null) return fallback;
        return findUrlBarByContentDescription(root);
    }

    private boolean redirectAddressBarInPlace(
            BrowserSupport.Config config, AccessibilityNodeInfo bar) {
        String safeUrl = config.safeAddress;

        // Chromium exposes a read-only URL node until the omnibox is activated. Click it,
        // then reacquire the editable node on the scheduled retry.
        if (!bar.isEditable()) {
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return false;
        }

        bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle setText = new Bundle();
        setText.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeUrl);
        boolean replaced = bar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText);
        if (!replaced) {
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.w(TAG, "Browser rejected the in-place address replacement");
            return false;
        }

        // Update detection state only after the browser confirms that its actual address
        // editor accepted the replacement.
        lastObservedUrls.put(config.packageName, safeUrl);
        lastUrlChangeTimes.put(config.packageName, SystemClock.elapsedRealtime());

        boolean submitted = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = bar.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }

        if (!submitted) {
            // Android 8-10 lack the IME-enter action, and some newer Chromium builds
            // expose it but reject it. Retry through the browser's exact safe suggestion.
            Log.w(TAG, "Safe address inserted; retrying through the exact omnibox suggestion");
            urlCheckHandler.postDelayed(() -> retrySafeAddressSubmission(config, safeUrl), 200);
        }
        return true;
    }

    private void retrySafeAddressSubmission(BrowserSupport.Config config, String safeUrl) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !config.packageName.equals(root.getPackageName().toString())) {
            if (root != null) root.recycle();
            return;
        }
        AccessibilityNodeInfo bar = findAddressBar(root, config);
        boolean submitted = false;
        if (bar != null && bar.getText() != null
                && safeUrl.contentEquals(bar.getText())
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = bar.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        if (bar != null) bar.recycle();
        if (!submitted) clickExactAddressSuggestion(root, safeUrl);
        root.recycle();
    }

    /**
     * Some Chromium builds expose no working ACTION_IME_ENTER even on Android 11+.
     * Their omnibox does expose the exact safe address as a suggestion, so click its
     * nearest clickable ancestor. Exact equality prevents selecting search/history noise.
     */
    private boolean clickExactAddressSuggestion(AccessibilityNodeInfo root, String safeUrl) {
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(safeUrl);
        if (matches == null) return false;
        boolean clicked = false;
        for (AccessibilityNodeInfo match : matches) {
            // Ignore the omnibox editor itself; clicking it only preserves edit mode.
            // We need the separate, non-editable suggestion row with the same text.
            if (!clicked && !match.isEditable() && match.getText() != null
                    && safeUrl.equalsIgnoreCase(match.getText().toString().trim())) {
                AccessibilityNodeInfo candidate = AccessibilityNodeInfo.obtain(match);
                for (int depth = 0; candidate != null && depth < 6; depth++) {
                    if (candidate.isClickable()) {
                        clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        candidate.recycle();
                        candidate = null;
                        break;
                    }
                    AccessibilityNodeInfo parent = candidate.getParent();
                    candidate.recycle();
                    candidate = parent;
                }
                if (candidate != null) candidate.recycle();
            }
            match.recycle();
        }
        return clicked;
    }

    private void showSessionCompleteRedirectMessage() {
        Toast.makeText(this, R.string.session_complete_redirect_message, Toast.LENGTH_SHORT).show();
    }

    private boolean isKnownSafeNewTab(String url) {
        if (url == null) return true;
        String u = url.toLowerCase(Locale.ROOT);
        return u.equals("about:blank") ||
               u.contains("newtab") ||
               u.contains("chrome://newtab") ||
               u.contains("brave://newtab") ||
               u.contains("about:home");
    }

    private void confirmSafeState(String packageName) {
        if (!restrictedUseConfirmed) return;
        restrictedUseConfirmed = false;
        lastRestrictedSeenAt = 0;
        lastSafeSeenAt = 0;
        updateRestrictedTimer(false);
    }

    private void updateRestrictedTimer(boolean isRestricted) {
        if (activeStickyPackage == null) isRestricted = false;
        long now = SystemClock.elapsedRealtime();

        if (isRestricted) {
            if (!restrictedUseConfirmed) {
                restrictedUseConfirmed = true;
            }
            if (restrictedSegmentStartedAt == 0) {
                restrictedSegmentStartedAt = now;
                notificationHandler.post(notificationTicker);
            }
        } else {
            if (restrictedSegmentStartedAt != 0) {
                accumulatedRestrictedTimeMs += (now - restrictedSegmentStartedAt);
                restrictedSegmentStartedAt = 0;
                notificationHandler.removeCallbacks(notificationTicker);
            }
            restrictedUseConfirmed = false;
        }
    }

    private void checkLiveBudgetExhaustion() {
        if (activeStickyPackage == null || isBudgetLockedOut) return;
        if (sessionLimitSeconds <= 0) return;

        long totalRestrictedTimeMs = getCurrentRestrictedTimeMs();
        long usedSeconds = attentionBudgetEngine.calculateUsageSeconds(totalRestrictedTimeMs);

        if (usedSeconds >= sessionLimitSeconds) {
            isBudgetLockedOut = true;
            sessionLimitReached = true;
            String exhaustedPackage = activeStickyPackage;
            endStickySession();
            if (isBrowserPackage(exhaustedPackage)) {
                BrowserSupport.Config browser = findBrowserConfig(exhaustedPackage);
                if (browser != null) beginBrowserRedirect(browser);
                lastDecisionGateTime = SystemClock.elapsedRealtime();
                showSessionCompleteRedirectMessage();
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }
    }

    private void startStickySession(String packageName) {
        isBudgetLockedOut = false;
        sessionLimitSeconds = attentionBudgetEngine.quoteSessionSeconds(
                appPreferencesManager.getPendingQuotedSessionSeconds());
        if (sessionLimitSeconds <= 0) {
            isBudgetLockedOut = true;
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        sessionLimitReached = false;
        grayscaleController.setGrayscaleEnabled(true);

        activeStickyPackage = packageName;
        sessionApprovedPatterns.clear();
        String interceptedUrl = appPreferencesManager.getLastInterceptedUrl();
        if (!interceptedUrl.isEmpty()) sessionApprovedPatterns.add(interceptedUrl);

        accumulatedRestrictedTimeMs = 0;
        persistedRestrictedTimeMs = 0;
        persistedUsageSeconds = 0;
        restrictedUseConfirmed = false;
        restrictedSegmentStartedAt = 0;
        lastCheckpointAt = 0;

        attentionBudgetEngine.incrementSessionCount();

        // Interception kind is explicit: a blocked browser app is metered as an app, while
        // a URL-only browser session pauses metering as soon as the user leaves that URL.
        if (InterceptionPolicy.shouldStartSessionTimer(
                appPreferencesManager.getLastInterceptionKind(), isBrowserPackage(packageName),
                !interceptedUrl.isEmpty())) {
            updateRestrictedTimer(true);
        }
    }

    private boolean isBrowserPackage(String packageName) {
        return findBrowserConfig(packageName) != null;
    }

    private BrowserSupport.Config findBrowserConfig(String packageName) {
        if (packageName == null) return null;
        return supportedBrowserByPackage.get(packageName);
    }

    private void endStickySession() {
        updateRestrictedTimer(false);
        checkpointSessionUsage(true);
        if (persistedRestrictedTimeMs > 0) {
            appPreferencesManager.recordSessionOutcome(sessionLimitReached);
        }
        grayscaleController.setGrayscaleEnabled(false);
        activeStickyPackage = null;
        sessionApprovedPatterns.clear();
        accumulatedRestrictedTimeMs = 0;
        persistedRestrictedTimeMs = 0;
        persistedUsageSeconds = 0;
        sessionLimitSeconds = 0;
        sessionLimitReached = false;
        restrictedSegmentStartedAt = 0;
        restrictedUseConfirmed = false;
        notificationHandler.removeCallbacks(notificationTicker);
        if (!destroyed) updateStatsNotification();
    }

    private long getCurrentRestrictedTimeMs() {
        long currentSegment = restrictedSegmentStartedAt == 0
                ? 0 : Math.max(0, SystemClock.elapsedRealtime() - restrictedSegmentStartedAt);
        return Math.max(0, accumulatedRestrictedTimeMs + currentSegment);
    }

    /** Checkpoint in small batches; a final forced write runs at every session boundary. */
    private void checkpointSessionUsage(boolean force) {
        if (activeStickyPackage == null || attentionBudgetEngine == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheckpointAt < CHECKPOINT_INTERVAL_MS) return;
        long totalTime = getCurrentRestrictedTimeMs();
        if (totalTime <= persistedRestrictedTimeMs) return;
        long totalUsedSeconds = attentionBudgetEngine.calculateUsageSeconds(totalTime);
        long durationDelta = totalTime - persistedRestrictedTimeMs;
        long usageDeltaSeconds = Math.max(0, totalUsedSeconds - persistedUsageSeconds);
        attentionBudgetEngine.recordUsageDelta(durationDelta, usageDeltaSeconds);
        persistedRestrictedTimeMs = totalTime;
        persistedUsageSeconds = totalUsedSeconds;
        lastCheckpointAt = now;
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
        String p = packageName.toLowerCase(Locale.ROOT);
        return p.equals("android") || p.contains("systemui") || p.contains("statusbar") || 
               p.contains("notification") || p.contains("quicksettings");
    }

    private boolean isLauncherPackage(String packageName) {
        if (packageName == null) return false;
        if (launcherPackages.contains(packageName)) return true;
        String p = packageName.toLowerCase(Locale.ROOT);
        return p.contains("launcher") || p.contains("trebuchet") || p.contains("home") || 
               p.contains("nexuslauncher") || p.contains("miui.home") || p.contains("pixel") ||
               p.contains("launcher3") || p.contains("launcher2");
    }

    private void triggerDecisionGate() {
        triggerDecisionGate(false);
    }

    private void triggerDecisionGate(boolean strict) {
        lastDecisionGateTime = SystemClock.elapsedRealtime();
        pendingBrowserAddressClear = null;

        if (activeStickyPackage != null) {
            endStickySession();
        }

        // Reset foreground tracking so the restricted app is always re-detected when
        // it returns to foreground, regardless of how the gate was dismissed (Cancel
        // button, hardware back, or system navigation). Without this, the gate never
        // re-fires if lastForegroundPackage still equals the restricted app's package.
        lastForegroundPackage = null;

        Intent intent = new Intent(this, DecisionGateActivity.class);
        intent.putExtra(DecisionGateActivity.EXTRA_STRICT_BLOCK, strict);
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
        String cls = lastWindowClassName.toLowerCase(Locale.ROOT);
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
        return findTextRecursiveInternal(node, text.toLowerCase(Locale.ROOT), 0);
    }

    // MEDIUM-03: Depth-limited to prevent StackOverflowError on deep accessibility trees.
    private boolean findTextRecursiveInternal(AccessibilityNodeInfo node, String lowerText, int depth) {
        if (node == null || depth > 30) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase(Locale.ROOT).contains(lowerText)) return true;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase(Locale.ROOT).contains(lowerText)) return true;
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
        return findNodeByContentDescription(root, Arrays.asList("address", "url", "search bar"), 0);
    }

    private AccessibilityNodeInfo findNodeByContentDescription(
            AccessibilityNodeInfo node, List<String> hints, int depth) {
        if (node == null || depth > 30) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                if (d.contains(hint)) return AccessibilityNodeInfo.obtain(node);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeByContentDescription(child, hints, depth + 1);
            if (child != null) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    @Override public void onInterrupt() {
        checkpointSessionUsage(true);
    }

    @Override public void onDestroy() {
        destroyed = true;
        notificationHandler.removeCallbacksAndMessages(null);
        urlCheckHandler.removeCallbacksAndMessages(null);
        pendingUrlChecks.clear();
        browserRedirectAttempts.clear();
        supportedBrowserByPackage.clear();
        if (blockPageServer != null) {
            blockPageServer.close();
            blockPageServer = null;
        }
        if (activeStickyPackage != null) endStickySession();
        else if (grayscaleController != null) grayscaleController.setGrayscaleEnabled(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        instance = null;
        super.onDestroy();
    }

    @NonNull
    private List<BrowserSupport.Config> getSupportedBrowsers(String blockPageAddress) {
        Set<String> discoveredPackages = new HashSet<>();
        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
        browserIntent.addCategory(Intent.CATEGORY_APP_BROWSER);
        List<ResolveInfo> browsers = getPackageManager().queryIntentActivities(
                browserIntent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo browser : browsers) {
            if (browser.activityInfo != null && browser.activityInfo.packageName != null) {
                discoveredPackages.add(browser.activityInfo.packageName);
            }
        }
        return BrowserSupport.withDiscoveredPackages(discoveredPackages, blockPageAddress);
    }
}
