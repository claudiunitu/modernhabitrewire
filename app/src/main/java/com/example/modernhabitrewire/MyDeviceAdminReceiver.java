package com.example.modernhabitrewire;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Disabling device administration will remove app protection.";
    }
}
