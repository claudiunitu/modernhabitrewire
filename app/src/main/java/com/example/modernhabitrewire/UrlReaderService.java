package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;


public class UrlReaderService extends AccessibilityService {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String APP_NAME = "Modern Habit Rewire";
    private static final String APP_INFO_TITLE = "App info";
    private AppPreferencesManager appPreferencesManager;

    public UrlReaderService() {


        this.supportedBrowsers = this.getSupportedBrowsers();
    }

    private BroadcastReceiver chargerBroadcastReceiver;

    private final String NOTIFICATION_CHANNEL_ID = "123456789";
    private final int NOTIFICATION_ID = 987456321;
    private final List<SupportedBrowserConfig> supportedBrowsers;
    private AppPreferencesManager urlManager;


    @Override
    public void onInterrupt() {
//        Log.e("UrlReaderService", "Error.");
        this.onServiceClose();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appPreferencesManager = new AppPreferencesManager(this);
        urlManager = new AppPreferencesManager(this);
        createNotification();
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
        this.registerChargerBroadcastReceiver();
    }

    private void onServiceClose() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.cancel(this.NOTIFICATION_ID);
        this.unregisterBroadcastReceiver();
        this.showServiceStopToastNotification();
        if (chargerBroadcastReceiver != null) {
            unregisterReceiver(chargerBroadcastReceiver);
            chargerBroadcastReceiver = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        try {
            if(!appPreferencesManager.getIsBlockerActive()) {
                return;
            }
            final int eventType = accessibilityEvent.getEventType();
            if(eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

                AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
                if (parentNodeInfo == null) {
                    return;
                }

                String packageName = accessibilityEvent.getPackageName().toString();



                this.cascadeRedirect( parentNodeInfo, packageName);

                parentNodeInfo.recycle();

            }
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

    private void createNotification() {
//        Log.d("UrlReaderService", "Creating notification");
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel channel = new NotificationChannel(
                    this.NOTIFICATION_CHANNEL_ID,
                    "Modern Habit Rewire Notification Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            notificationManager.notify(this.NOTIFICATION_ID,this.buildNotification());
        }

    }

    private Notification buildNotification() {

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, this.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Modern Habit Rewire Blocking Service")
            .setContentText("Blocking stuff")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setColor(Color.GREEN);

        return builder.build();


    }

    private void cascadeRedirect(AccessibilityNodeInfo parentNodeInfo, String packageName) {
        // check if it is a forbidden package
        if(this.redirectIfForbiddenPackage(packageName)) {
            return;
        }

        // check if forbidden url
        if(this.redirectIfForbiddenUrl (parentNodeInfo,packageName) ){
            return;
        }

        // forbid accessing the app settings in order to prevent uninstalling or stopping the app
        if( appPreferencesManager.getForbidSettingsSwitchValue()){
            redirectIfOpenedThisAppInSettings(parentNodeInfo,packageName);
            return;
        }

    }



    private boolean redirectIfOpenedThisAppInSettings(AccessibilityNodeInfo node, String packageName) {

        // TODO: the pattern to recognize that the device is on the current app OS settings page
        if (node == null || !SETTINGS_PACKAGE.equals(packageName)) {
            return false;
        }

        performRedirect();
        showToast("Uninstalling the app through settings is not allowed.");
        Log.d("AccessibilityService", "Prevented uninstall attempt via settings.");
        return true;

//        AccessibilityNodeInfo root = getRootInActiveWindow();
//        if (root == null) {
//            return false;
//        }



//        boolean isAppName = findRecursiveNodesWithContent(root, APP_NAME);
//        boolean containsTitleInfo = findRecursiveNodesWithContent(root, "info");
//        boolean containsPermissionInfo = findRecursiveNodesWithContent(root, "permi");
//        boolean containsNotificationInfo = findRecursiveNodesWithContent(root, "notif");
//        boolean containsVersionInfo = findRecursiveNodesWithContent(root, "vers");

//        if (containsTitleInfo) {
//            performRedirect();
//            showToast("Uninstalling the app through settings is not allowed.");
//            Log.d("AccessibilityService", "Prevented uninstall attempt via settings.");
//            return true;
//        }
//
//        return false;
    }


    private Boolean redirectIfForbiddenPackage(String packageName) {
        if(isForbiddenPackage(packageName)) {
//            Log.d("UrlReaderService", packageName + "  :  " + "denied");
            this.performRedirect();
            this.showToast("Forbidden app: " + packageName);
            return true;
        }
        return false;
    }

    private Boolean redirectIfForbiddenUrl (AccessibilityNodeInfo parentNodeInfo,String packageName){
        // check if the event was triggered by a browser and if it accessed a forbidden url
        SupportedBrowserConfig browserConfig = null;
        for (SupportedBrowserConfig supportedConfig: supportedBrowsers) {
            if (supportedConfig.packageName.equals(packageName)) {
                browserConfig = supportedConfig;
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
        browsers.add( new SupportedBrowserConfig("org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"));
        browsers.add( new SupportedBrowserConfig("com.opera.browser", "com.opera.browser:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.opera.mini.native", "com.opera.mini.native:id/url_field"));
        browsers.add( new SupportedBrowserConfig("com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput"));
        browsers.add( new SupportedBrowserConfig("com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"));
        browsers.add( new SupportedBrowserConfig("com.brave.browser", "com.brave.browser:id/url_bar"));


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
        List<String> forbiddenUrls = urlManager.getForbiddenUrls();
        for (String forbiddenUrlPattern : forbiddenUrls) {
            if (url.contains(forbiddenUrlPattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForbiddenPackage(String packageId) {
        for (String forbiddenPackageName : urlManager.getForbiddenApps()) {
            if (packageId.equals(forbiddenPackageName)) {
                return true;
            }
        }
        return false;
    }





    private void registerChargerBroadcastReceiver() {
        this.chargerBroadcastReceiver = new ChargingState();
        IntentFilter filter =new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        this.registerReceiver(chargerBroadcastReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        unregisterReceiver(this.chargerBroadcastReceiver);
    }

    private Boolean findRecursiveNodesWithContent(AccessibilityNodeInfo node, String query) {
        if (node == null) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if ((text != null && text.toString().toLowerCase().contains(query.toLowerCase())) ||
                (desc != null && desc.toString().toLowerCase().contains(query.toLowerCase()))) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            Boolean found = findRecursiveNodesWithContent(node.getChild(i), query);
            if (found) {
                return true;
            }

        }
        return false;
    }
}