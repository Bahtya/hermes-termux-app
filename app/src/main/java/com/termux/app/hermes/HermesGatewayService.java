package com.termux.app.hermes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HermesGatewayService extends Service {

    private static final String TAG = "HermesGateway";
    private static final int NOTIFICATION_ID = 2001;
    private static final int ERROR_NOTIFICATION_ID = 2002;
    private static final String CHANNEL_ID = "hermes_gateway_channel";
    private static final String ERROR_CHANNEL_ID = "hermes_gateway_errors";
    public static final String ACTION_START = "com.hermes.termux.GATEWAY_START";
    public static final String ACTION_STOP = "com.hermes.termux.GATEWAY_STOP";
    public static final String ACTION_CHECK = "com.hermes.termux.GATEWAY_CHECK";
    public static final String ACTION_RESTART = "com.hermes.termux.GATEWAY_RESTART";
    public static final String PREF_AUTO_START = "hermes_auto_start_gateway";
    private static final String PREF_RESTART_COUNT = "gateway_restart_count";
    private static final String PREF_LAST_CRASH_TIME = "gateway_last_crash_time";
    private static final long STABLE_UPTIME_MS = 60_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Process mGatewayProcess;
    private boolean mRunning = false;
    private int mRestartAttempts = 0;
    private static final int MAX_RESTARTS = 3;
    private long mProcessStartTime = 0;

    private final Runnable mHealthCheck = new Runnable() {
        @Override
        public void run() {
            if (mRunning) {
                boolean alive = mGatewayProcess != null && mGatewayProcess.isAlive();
                if (!alive && mRestartAttempts < MAX_RESTARTS) {
                    Log.w(TAG, "Gateway process died, restarting (attempt " + (mRestartAttempts + 1) + ")");
                    showCrashNotification(mRestartAttempts + 1);
                    startGatewayProcess();
                    mRestartAttempts++;
                    saveRestartState();
                } else if (!alive) {
                    Log.e(TAG, "Gateway failed after " + MAX_RESTARTS + " restart attempts");
                    showErrorNotification();
                    mRunning = false;
                } else {
                    // Process is alive — reset restart count after stable uptime
                    long uptime = System.currentTimeMillis() - mProcessStartTime;
                    if (uptime > STABLE_UPTIME_MS && mRestartAttempts > 0) {
                        Log.i(TAG, "Gateway stable for 60s, resetting restart count");
                        mRestartAttempts = 0;
                        clearRestartState();
                    }
                }
                mHandler.postDelayed(this, 30_000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopGateway();
                return START_NOT_STICKY;
            }
            if (ACTION_RESTART.equals(action)) {
                clearRestartState();
                mRestartAttempts = 0;
                cancelErrorNotification();
                if (!mRunning) {
                    startGatewayProcess();
                }
                return START_STICKY;
            }
        }

        if (!mRunning) {
            mRestartAttempts = loadRestartCount();
            startGatewayProcess();
        }
        return START_STICKY;
    }

    private void startGatewayProcess() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String hermesPath = binPath + "/hermes";

        if (!new File(hermesPath).exists()) {
            Log.w(TAG, "Hermes binary not found at " + hermesPath);
            updateNotification("Hermes not installed");
            return;
        }

        HermesConfigManager config = HermesConfigManager.getInstance();
        String logDir = home + "/.hermes/logs";
        new File(logDir).mkdirs();

        mExecutor.execute(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        binPath + "/bash", "-c",
                        hermesPath + " gateway run >> " + logDir + "/gateway.log 2>&1"
                );
                pb.environment().put("HOME", home);
                pb.environment().put("PATH", binPath + ":/system/bin:/system/xbin");
                pb.environment().put("TERMUX_HOME", home);
                pb.environment().put("TERMUX_PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);

                String envFile = home + "/.hermes/.env";
                if (new File(envFile).exists()) {
                    pb.environment().put("HERMES_ENV_FILE", envFile);
                }

                mGatewayProcess = pb.start();
                mRunning = true;
                mProcessStartTime = System.currentTimeMillis();
                if (mRestartAttempts == 0) {
                    clearRestartState();
                }

                cancelErrorNotification();
                Notification notification = buildNotification("Hermes Gateway running");
                startForeground(NOTIFICATION_ID, notification);

                mHandler.postDelayed(mHealthCheck, 30_000);
                Log.i(TAG, "Gateway process started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start gateway", e);
                updateNotification("Gateway error: " + e.getMessage());
            }
        });
    }

    private void stopGateway() {
        mRunning = false;
        mHandler.removeCallbacks(mHealthCheck);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-c",
                    "pkill -f 'hermes gateway' 2>/dev/null; echo done"
            );
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            pb.start();
        } catch (Exception ignored) {}

        if (mGatewayProcess != null) {
            mGatewayProcess.destroy();
            mGatewayProcess = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, TermuxActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HermesGatewayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes Gateway")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_hermes)
                .setContentIntent(pi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Hermes Gateway",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Hermes gateway status");
            nm.createNotificationChannel(channel);

            NotificationChannel errorChannel = new NotificationChannel(
                    ERROR_CHANNEL_ID,
                    "Hermes Gateway Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            errorChannel.setDescription("Gateway crash and restart alerts");
            nm.createNotificationChannel(errorChannel);
        }
    }

    private void showCrashNotification(int attempt) {
        Notification notification = new NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
                .setContentTitle("Hermes Gateway Restarted")
                .setContentText("Gateway crashed and was restarted (attempt " + attempt + "/" + MAX_RESTARTS + ")")
                .setSmallIcon(R.drawable.ic_hermes)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ERROR_NOTIFICATION_ID, notification);
    }

    private void showErrorNotification() {
        Intent restartIntent = new Intent(this, HermesGatewayService.class);
        restartIntent.setAction(ACTION_RESTART);
        PendingIntent restartPi = PendingIntent.getService(this, 2, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
                .setContentTitle("Hermes Gateway Stopped")
                .setContentText("Gateway crashed " + MAX_RESTARTS + " times and could not recover")
                .setSmallIcon(R.drawable.ic_hermes)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(0, "Restart", restartPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ERROR_NOTIFICATION_ID, notification);
    }

    private void cancelErrorNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ERROR_NOTIFICATION_ID);
    }

    private void saveRestartState() {
        getSharedPreferences("hermes_gateway", MODE_PRIVATE)
                .edit()
                .putInt(PREF_RESTART_COUNT, mRestartAttempts)
                .putLong(PREF_LAST_CRASH_TIME, System.currentTimeMillis())
                .apply();
    }

    private void clearRestartState() {
        getSharedPreferences("hermes_gateway", MODE_PRIVATE)
                .edit()
                .remove(PREF_RESTART_COUNT)
                .remove(PREF_LAST_CRASH_TIME)
                .apply();
    }

    private int loadRestartCount() {
        return getSharedPreferences("hermes_gateway", MODE_PRIVATE)
                .getInt(PREF_RESTART_COUNT, 0);
    }

    @Override
    public void onDestroy() {
        stopGateway();
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isAutoStartEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(PREF_AUTO_START, false);
    }

    public static void setAutoStartEnabled(Context context, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(PREF_AUTO_START, enabled)
                .apply();
    }

    public static void restartIfRunning(Context context) {
        HermesGatewayStatus.checkAsync((status, detail) -> {
            if (status == HermesGatewayStatus.Status.RUNNING) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    context.startService(new Intent(context, HermesGatewayService.class)
                            .setAction(ACTION_STOP));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        context.startService(new Intent(context, HermesGatewayService.class)
                                .setAction(ACTION_START));
                    }, 1500);
                }, 500);
            }
        });
    }
}
