package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class UrlReaderService extends AccessibilityService {

    public UrlReaderService() {

        Log.d("UrlReaderService", "Instantiated.");





        this.supportedBrowsers = this.getSupportedBrowsers();
    }

    private List<SupportedBrowserConfig> supportedBrowsers;
    private String[] forbiddenUrlPatterns =  {
            "facebook.com",
            "9gag.com",
            "youtube.com",
            "mediafax.ro",
            "hotnews.ro",
            "digi24.ro",
            "antena3.ro",
            "realitatea.net",
            "rt.com"
    };
    private String[] forbiddenPackageNames = {
            "com.google.android.youtube"
    };




    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        if(ChargingState.isCharging) {
            return;
        }
        final int eventType = accessibilityEvent.getEventType();
        switch(eventType) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {

                AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
                if (parentNodeInfo == null) {
                    return;
                }

                String packageName = accessibilityEvent.getPackageName().toString();

                // check if it is a forbidden package
                if(isForbiddenPackage(packageName)) {
                    Log.d("UrlReaderService", packageName + "  :  " + "denied");
                    this.performRedirect("localhost", packageName);
                    return;
                }


                // check if the event was triggered by a browser and if it accessed a forbidden url
                SupportedBrowserConfig browserConfig = null;
                for (SupportedBrowserConfig supportedConfig: supportedBrowsers) {
                    if (supportedConfig.packageName.equals(packageName)) {
                        browserConfig = supportedConfig;
                    }
                }
                //this is not supported browser, so exit
                if (browserConfig == null) {
                    Log.d("UrlReaderService", "Not a browser.");
                    return;
                }

                String capturedUrl = captureUrl(parentNodeInfo, browserConfig);
                parentNodeInfo.recycle();

                if (capturedUrl == null) {
                    return;
                }

                if(android.util.Patterns.WEB_URL.matcher(capturedUrl).matches() && this.isForbiddenWebsite(capturedUrl)) {
                    Log.d("UrlReaderService", packageName + "  :  " + capturedUrl);
                    this.performRedirect("localhost", packageName);
                    return;
                }




            }
            break;
        }
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
        browsers.add( new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"));
        browsers.add( new SupportedBrowserConfig("com.opera.browser", "com.opera.browser:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.opera.mini.native", "com.opera.mini.native:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput"));
        browsers.add( new SupportedBrowserConfig("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"));


        return browsers;
    }
    private void getChild(AccessibilityNodeInfo info)
    {
        int i=info.getChildCount();
        for(int p=0;p<i;p++)
        {
            AccessibilityNodeInfo n=info.getChild(p);
            if(n!=null) {
                String strres = n.getViewIdResourceName();
                if (n.getText() != null) {
                    String txt = n.getText().toString();
                    Log.d("Track", strres + "  :  " + txt);
                }
                getChild(n);
            }
        }
    }
    private String captureUrl(AccessibilityNodeInfo info, SupportedBrowserConfig config) {

        List<AccessibilityNodeInfo> nodes = info.findAccessibilityNodeInfosByViewId(config.addressBarId);
        if (nodes == null || nodes.size() <= 0) {
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

    private void performRedirect(String redirectUrl, String browserPackage) {

        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);

    }

    private boolean isForbiddenWebsite(String url) {
        for (int i = 0; i < this.forbiddenUrlPatterns.length; i++) {
            if(url.contains(this.forbiddenUrlPatterns[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean isForbiddenPackage(String packageId) {
        for (int i = 0; i < this.forbiddenPackageNames.length; i++) {
            if(packageId.equals(this.forbiddenPackageNames[i])) {
                return true;
            }
        }
        return false;
    }


    @Override
    public void onInterrupt() {
        Log.d("UrlReaderService", "Error.");
    }

    @Override
    public void onServiceConnected() {

        Log.d("UrlReaderService", "Connected.");
        this.registerChargerBroadcastReceiver();

    }

    private void registerChargerBroadcastReceiver() {
        BroadcastReceiver chargerBroadcastReceiver = new ChargingState();
        IntentFilter filter =new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        this.registerReceiver(chargerBroadcastReceiver, filter);
    }
}