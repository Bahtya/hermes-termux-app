package com.termux.app.hermes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class HermesBootReceiver extends BroadcastReceiver {

    private static final String TAG = "HermesBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean autoStart = HermesGatewayService.isAutoStartEnabled(context);
        if (!autoStart) {
            Log.i(TAG, "Boot auto-start disabled, skipping");
            return;
        }

        Log.i(TAG, "Boot completed, starting Hermes gateway in 10s");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                context.startService(new Intent(context, HermesGatewayService.class)
                        .setAction(HermesGatewayService.ACTION_START));
                Log.i(TAG, "Gateway start service triggered");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start gateway on boot", e);
            }
        }, 10_000);
    }
}
