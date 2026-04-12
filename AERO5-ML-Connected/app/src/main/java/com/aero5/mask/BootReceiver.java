package com.aero5.mask;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // Check if user was logged in
            SharedPreferences prefs = context.getSharedPreferences("aero5", Context.MODE_PRIVATE);
            String maskId = prefs.getString("mask_id", "");

            if (!maskId.isEmpty()) {
                Log.d("AERO5Boot", "Restarting background service for: " + maskId);
                Intent svc = new Intent(context, AeroBackgroundService.class);
                svc.setAction(AeroBackgroundService.ACTION_START);
                svc.putExtra("maskId", maskId);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }
        }
    }
}
