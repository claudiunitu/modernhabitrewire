package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

public class ScreenReaderAccessibilityService extends AccessibilityService {
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public ScreenReaderAccessibilityService() {
        this.supportedBrowsers = this.getSupportedBrowsers();
    }

    private final List<SupportedBrowserConfig> supportedBrowsers;

    @Override
    public void onInterrupt() {
        this.onServiceClose();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.onServiceClose();
    }

    @Override
    public void onServiceConnected() {
        this.showServiceStartToastNotification();
    }

    private void onServiceClose() {
        this.showServiceStopToastNotification();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        try {
            if (!appPreferencesManagerSingleton.getIsBlockerActive()) {
                return;
            }

            CharSequence packageName = accessibilityEvent.getPackageName();
            if (packageName == null) {
                return;
            }

            // App-blocking logic has been removed. This service now only handles URL blocking.
            redirectIfForbiddenUrl(accessibilityEvent, packageName);

        } catch (Exception e) {
            // It's better to log the exception than to show a toast for a background service.
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showServiceStartToastNotification() {
        this.showToast("Modern Habit Rewire Service Started");
    }

    private void showServiceStopToastNotification() {
        this.showToast("Modern Habit Rewire Service Stopped");
    }

    private boolean redirectIfForbiddenUrl(AccessibilityEvent accessibilityEvent, CharSequence packageName) {
        CharSequence className = accessibilityEvent.getClassName();
        // This service should only care about edits to EditText fields (like address bars)
        if (className == null || !className.equals("android.widget.EditText")) {
            return false;
        }

        SupportedBrowserConfig browserConfig = null;
        for (SupportedBrowserConfig supportedConfig : supportedBrowsers) {
            if (packageName.equals(supportedConfig.packageName)) {
                browserConfig = supportedConfig;
                break;
            }
        }

        if (browserConfig == null) {
            return false; // Not a supported browser
        }

        AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
        if (parentNodeInfo == null) {
            return false;
        }

        String capturedUrl = captureUrl(parentNodeInfo, browserConfig);
        parentNodeInfo.recycle();

        if (capturedUrl != null && android.util.Patterns.WEB_URL.matcher(capturedUrl).matches() && this.isForbiddenWebsite(capturedUrl)) {
            this.performRedirect();
            this.showToast("Forbidden url: " + capturedUrl);
            return true;
        }
        return false;
    }

    private static class SupportedBrowserConfig {
        public String packageName, addressBarId;
        public SupportedBrowserConfig(String packageName, String addressBarId) {
            this.packageName = packageName;
            this.addressBarId = addressBarId;
        }
    }

    @NonNull
    private List<SupportedBrowserConfig> getSupportedBrowsers() {
        List<SupportedBrowserConfig> browsers = new ArrayList<>();
        browsers.add(new SupportedBrowserConfig("com.android.chrome", "com.android.chrome:id/url_bar"));
        browsers.add(new SupportedBrowserConfig("com.brave.browser", "com.brave.browser:id/url_bar"));
        browsers.add(new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"));
        browsers.add(new SupportedBrowserConfig("com.opera.browser", "com.opera.browser:id/url_field"));
        browsers.add(new SupportedBrowserConfig("com.opera.mini.native", "com.opera.mini.native:id/url_field"));
        browsers.add(new SupportedBrowserConfig("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput"));
        browsers.add(new SupportedBrowserConfig("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"));
        return browsers;
    }

    private String captureUrl(AccessibilityNodeInfo parentNodeInfo, SupportedBrowserConfig config) {
        List<AccessibilityNodeInfo> nodes = parentNodeInfo.findAccessibilityNodeInfosByViewId(config.addressBarId);
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        AccessibilityNodeInfo addressBarNodeInfo = nodes.get(0);
        String url = null;
        // The !isFocused() check was removed to reliably capture URLs across different browsers and states.
        if (addressBarNodeInfo.getText() != null) {
            url = addressBarNodeInfo.getText().toString();
        }
        addressBarNodeInfo.recycle();
        return url;
    }

    private void performRedirect() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    private boolean isForbiddenWebsite(String url) {
        List<String> forbiddenUrls = appPreferencesManagerSingleton.getForbiddenUrls();
        for (String forbiddenUrlPattern : forbiddenUrls) {
            if (url.contains(forbiddenUrlPattern)) {
                return true;
            }
        }
        return false;
    }
}
