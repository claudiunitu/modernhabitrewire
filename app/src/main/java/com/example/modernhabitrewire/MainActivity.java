package com.example.modernhabitrewire;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends AppCompatActivity {

    public static final int REQUEST_CODE_ENABLE_ADMIN = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d("MainActivity", "Created.");

        this.requestDeviceAdminPermission();

        this.requestNotificationPermission();

        this.requestAccessibilityPermission();

    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    private void requestDeviceAdminPermission() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, MyDeviceAdminReceiver.class));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable device administration to protect app uninstallation.");
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if(NotificationManagerCompat.from(this).areNotificationsEnabled() == false){
            Intent notificationSettingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            notificationSettingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, this.getPackageName());
            startActivity(notificationSettingsIntent);
        }
    }

    private void requestAccessibilityPermission(){
        Intent accessibilitySettingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(accessibilitySettingsIntent);
    }





}