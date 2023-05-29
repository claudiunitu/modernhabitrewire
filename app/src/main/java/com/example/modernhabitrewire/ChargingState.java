package com.example.modernhabitrewire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ChargingState extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
//        Log.d("ChargingBroadcast", intent.getAction());
        if(intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
            ChargingState.isCharging = true;
        } else if(intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
            ChargingState.isCharging = false;
        } else {
//            Log.d("ChargingBroadcast", "other");
        }
    }

    public static boolean isCharging = false;
}