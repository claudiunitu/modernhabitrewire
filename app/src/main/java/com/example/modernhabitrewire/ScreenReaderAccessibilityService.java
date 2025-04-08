package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;

import android.util.Log;
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
//        Log.e("UrlReaderService", "Error.");
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

//        Log.d("UrlReaderService", "Connected.");
        this.showServiceStartToastNotification();

    }

    private void onServiceClose() {
        this.showServiceStopToastNotification();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
//        Log.d("Content changed", "onAccessibilityEvent");
        try {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()) {
                return;
            }

            AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
            if (parentNodeInfo == null) {
                return;
            }

            CharSequence packageName = accessibilityEvent.getPackageName();

            this.cascadeRedirect( parentNodeInfo, packageName);

            parentNodeInfo.recycle();

        }
        catch(Exception e) {
            CharSequence errorText = e.getMessage();
            int duration = Toast.LENGTH_SHORT;

            Toast toast = Toast.makeText(this, errorText, duration);
            toast.show();
        }

    }


    private void showToast(String message){

        int duration = Toast.LENGTH_SHORT;

        Toast toast = Toast.makeText(this, message, duration);
        toast.show();
    }

    private void showServiceStartToastNotification() {
        this.showToast("Modern Habit Rewire Service Started");
    }

    private void showServiceStopToastNotification() {
        this.showToast("Modern Habit Rewire Service Stopped");
    }




    private void cascadeRedirect(AccessibilityNodeInfo parentNodeInfo, CharSequence packageName) {
        // check if it is a forbidden package
        if(this.redirectIfForbiddenPackage(packageName)) {
            return;
        }

        // check if forbidden url
        if(this.redirectIfForbiddenUrl (parentNodeInfo,packageName) ){
            return;
        }
    }


    private Boolean redirectIfForbiddenPackage(CharSequence packageName) {
        if(isForbiddenPackage(packageName)) {
//            Log.d("UrlReaderService", packageName + "  :  " + "denied");
            this.performRedirect();
            this.showToast("Forbidden app: " + packageName);
            return true;
        }
        return false;
    }

    private Boolean redirectIfForbiddenUrl (AccessibilityNodeInfo parentNodeInfo,CharSequence packageName){
        // check if the event was triggered by a browser and if it accessed a forbidden url
        SupportedBrowserConfig browserConfig = null;
        for (SupportedBrowserConfig supportedConfig: supportedBrowsers) {
            if (packageName.equals(supportedConfig.packageName)) {
                browserConfig = supportedConfig;
                break;
            }
        }
        //this is not supported browser, so exit
        if (browserConfig == null) {
//            Log.d("UrlReaderService", "Not a browser.");
            return false;
        }

        String capturedUrl = captureUrl(parentNodeInfo, browserConfig);


        if (capturedUrl == null) {
            return false;
        }

        if(android.util.Patterns.WEB_URL.matcher(capturedUrl).matches() && this.isForbiddenWebsite(capturedUrl)) {
//            Log.d("UrlReaderService", packageName + "  :  " + capturedUrl);
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
        browsers.add( new SupportedBrowserConfig("com.android.chrome", "com.android.chrome:id/url_bar"));
        browsers.add( new SupportedBrowserConfig("com.brave.browser", "com.brave.browser:id/url_bar"));
        browsers.add( new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"));
        browsers.add( new SupportedBrowserConfig("com.opera.browser", "com.opera.browser:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.opera.mini.native", "com.opera.mini.native:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput"));
        browsers.add( new SupportedBrowserConfig("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"));



        return browsers;
    }

    private String captureUrl(AccessibilityNodeInfo info, SupportedBrowserConfig config) {

        List<AccessibilityNodeInfo> nodes = info.findAccessibilityNodeInfosByViewId(config.addressBarId);
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        AccessibilityNodeInfo addressBarNodeInfo = nodes.get(0);
        String url = null;
        if (!addressBarNodeInfo.isFocused() && addressBarNodeInfo.getText() != null) {

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

    private boolean isForbiddenPackage(CharSequence packageId) {
        for (String forbiddenPackageName : appPreferencesManagerSingleton.getForbiddenApps()) {
            if (packageId.equals(forbiddenPackageName)) {
                return true;
            }
        }
        return false;
    }

}