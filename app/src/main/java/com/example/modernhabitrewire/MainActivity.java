package com.example.modernhabitrewire;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import android.provider.Settings;


import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        Log.d("MainActivity", "Created.");
        this.backButtonPressedDispatcher();
        this.requestDeviceAdminPermission();
        this.requestAccessibilityPermission();
        this.requestNotificationPermission();
        this.requestPostNotificationPermision();

    }

    @Override
    protected void onStart() {
        super.onStart();
    }



    private void backButtonPressedDispatcher(){
        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void requestDeviceAdminPermission() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, MyDeviceAdminReceiver.class));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable device administration to protect app closing and uninstallation.");
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled()){
            Intent notificationSettingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            notificationSettingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, this.getPackageName());
            startActivity(notificationSettingsIntent);
        }
    }
    private void requestPostNotificationPermision(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String permission = android.Manifest.permission.POST_NOTIFICATIONS;
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{ permission }, 1);
            }
        }
    }

    private void requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled(this, UrlReaderService.class)) {
            Intent accessibilitySettingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(accessibilitySettingsIntent);
        }
    }


//    probably won't work for accesibility service
//    private boolean isServiceRunning() {
//        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
//        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
//            if (UrlReaderService.class.getName().equals(service.service.getClassName())) {
//                return true;
//            }
//        }
//        return false;
//    }
    private boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        String prefString = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString == null) return false;
        final String serviceId = context.getPackageName() + "/" + service.getName();
        return prefString.toLowerCase().contains(serviceId.toLowerCase());
    }





}