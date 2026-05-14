package com.termux.app.hermes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;

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

        // Auto-start sshd after gateway
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String sshdPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sshd";
                if (new File(sshdPath).exists()) {
                    Runtime.getRuntime().exec(new String[]{
                            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-c",
                            sshdPath
                    });
                    Log.i(TAG, "sshd auto-started on boot");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start sshd on boot", e);
            }
        }, 15_000);
    }
}
