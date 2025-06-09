
package com.example.modernhabitrewire;

import android.accessibilityservice.AccessibilityService;

import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;


public class UninstallerForbidderAccessibilityService extends AccessibilityService {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String APP_NAME = "Modern Habit Rewire";
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;

    public UninstallerForbidderAccessibilityService() {}

    @Override
    public void onInterrupt() {
//        Log.e("UrlReaderService", "Error.");
        this.onServiceClose();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);
//        createNotification();
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
//        this.registerChargerBroadcastReceiver();
    }

    private void onServiceClose() {
        this.showServiceStopToastNotification();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
//        Log.d("State changed", "onAccessibilityEvent");
        try {
            if(!appPreferencesManagerSingleton.getIsBlockerActive()) {
                return;
            }



            CharSequence packageName = accessibilityEvent.getPackageName();

            this.cascadeRedirect( accessibilityEvent, packageName);



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


    private void cascadeRedirect(AccessibilityEvent accessibilityEvent, CharSequence packageName) {

        // forbid accessing the app settings in order to prevent uninstalling or stopping the app
        if( appPreferencesManagerSingleton.getForbidSettingsSwitchValue()){
            redirectIfOpenedThisAppInSettings(accessibilityEvent,packageName);
            return;
        }

    }

    private Boolean isPatternForCurrentAppInOSSettingsView(AccessibilityNodeInfo node, CharSequence packageName) {
        if (node == null || !packageName.equals(SETTINGS_PACKAGE)) {
            return false;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }

        if (!findRecursiveNodesWithContent(root, APP_NAME)) {
            return false;
        }

        return findRecursiveNodesWithContent(root, "info") ||
                findRecursiveNodesWithContent(root, "permi") ||
                findRecursiveNodesWithContent(root, "notif") ||
                findRecursiveNodesWithContent(root, "vers") ||
                findRecursiveNodesWithContent(root, "admin") ||
                findRecursiveNodesWithContent(root, "instal");
    }

    private boolean redirectIfOpenedThisAppInSettings(AccessibilityEvent accessibilityEvent, CharSequence packageName) {

        AccessibilityNodeInfo parentNodeInfo = accessibilityEvent.getSource();
        if (parentNodeInfo == null) {
            return false;
        }
        boolean isPatternForCurrentAppInOSSettingsView = isPatternForCurrentAppInOSSettingsView(parentNodeInfo, packageName);
        parentNodeInfo.recycle();


        if (isPatternForCurrentAppInOSSettingsView) {
            performRedirect();
            showToast("Uninstalling the app through settings is not allowed.");
//            Log.d("AccessibilityService", "Prevented uninstall attempt via settings.");
            return true;
        }
        return false;
    }

    private void performRedirect() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
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

            AccessibilityNodeInfo child = node.getChild(i);
            if(child == null){
                return false;
            }
            Boolean found = findRecursiveNodesWithContent(child, query);

            if (found) {
                child.recycle();
                return true;
            }
            child.recycle();

        }
        return false;
    }
}