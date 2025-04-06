package com.example.modernhabitrewire;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Objects;

public class ChargingState extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
//        Log.d("ChargingBroadcast", intent.getAction());
        if(Objects.equals(intent.getAction(), Intent.ACTION_POWER_CONNECTED)) {
            ChargingState.isCharging = true;
        } else if(Objects.equals(intent.getAction(), Intent.ACTION_POWER_DISCONNECTED)) {
            ChargingState.isCharging = false;
        } else {
//            Log.d("ChargingBroadcast", "other");
        }
    }

    public static boolean isCharging = false;
}