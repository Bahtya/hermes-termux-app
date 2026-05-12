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
    public static final String ACTION_START = "com.bahtya.GATEWAY_START";
    public static final String ACTION_STOP = "com.bahtya.GATEWAY_STOP";
    public static final String ACTION_CHECK = "com.bahtya.GATEWAY_CHECK";
    public static final String ACTION_RESTART = "com.bahtya.GATEWAY_RESTART";
    public static final String PREF_AUTO_START = "hermes_auto_start_gateway";
    private static final String PREF_RESTART_COUNT = "gateway_restart_count";
    private static final String PREF_LAST_CRASH_TIME = "gateway_last_crash_time";
    private static final long STABLE_UPTIME_MS = 60_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Process mGatewayProcess;
    private boolean mRunning = false;
    private int mRestartAttempts = 0;
    private static final int DEFAULT_MAX_RESTARTS = 3;
    private static final int DEFAULT_RESTART_DELAY_MS = 30_000;

    private int getMaxRestarts() {
        try {
            return Integer.parseInt(HermesConfigManager.getInstance()
                    .getEnvVar("GATEWAY_MAX_RESTARTS"));
        } catch (Exception e) {
            return DEFAULT_MAX_RESTARTS;
        }
    }

    private int getRestartDelayMs() {
        try {
            return Integer.parseInt(HermesConfigManager.getInstance()
                    .getEnvVar("GATEWAY_RESTART_DELAY")) * 1000;
        } catch (Exception e) {
            return DEFAULT_RESTART_DELAY_MS;
        }
    }

    private boolean isAutoRestartEnabled() {
        return !"false".equals(HermesConfigManager.getInstance()
                .getEnvVar("GATEWAY_AUTO_RESTART"));
    }
    private static final int TOTAL_STARTUP_STEPS = 5;
    private long mProcessStartTime = 0;
    private int mStartupStep = 0;

    // Static reference for uptime queries from activities
    private static volatile HermesGatewayService sInstance;

    private final Runnable mHealthCheck = new Runnable() {
        @Override
        public void run() {
            if (mRunning) {
                boolean alive = mGatewayProcess != null && mGatewayProcess.isAlive();
                if (!alive && isAutoRestartEnabled() && (getMaxRestarts() < 0 || mRestartAttempts < getMaxRestarts())) {
                    Log.w(TAG, "Gateway process died, restarting (attempt " + (mRestartAttempts + 1) + ")");
                    showCrashNotification(mRestartAttempts + 1);
                    startGatewayProcess();
                    mRestartAttempts++;
                    saveRestartState();
                } else if (!alive) {
                    Log.e(TAG, "Gateway failed after " + getMaxRestarts() + " restart attempts");
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
                    updateNotification(getString(R.string.gateway_notification_running, formatDuration(uptime)));
                }
                mHandler.postDelayed(this, 30_000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
    }

    private int getNotificationImportance() {
        try {
            return Integer.parseInt(HermesConfigManager.getInstance()
                    .getEnvVar("GATEWAY_NOTIF_IMPORTANCE"));
        } catch (Exception e) {
            return NotificationManager.IMPORTANCE_DEFAULT;
        }
    }

    private boolean isNotificationSoundEnabled() {
        return !"false".equals(HermesConfigManager.getInstance()
                .getEnvVar("GATEWAY_NOTIF_SOUND"));
    }

    private boolean isNotificationVibrateEnabled() {
        return "true".equals(HermesConfigManager.getInstance()
                .getEnvVar("GATEWAY_NOTIF_VIBRATE"));
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

        // Step 1: Loading configuration
        mStartupStep = 1;
        startForeground(NOTIFICATION_ID, buildProgressNotification(1, TOTAL_STARTUP_STEPS,
                getString(R.string.gateway_step_loading_config)));

        HermesConfigManager config = HermesConfigManager.getInstance();
        String logDir = home + "/.hermes/logs";
        new File(logDir).mkdirs();

        mExecutor.execute(() -> {
            try {
                // Step 2: Connecting to LLM provider
                updateStartupProgress(2, getString(R.string.gateway_step_connecting_llm));

                // Step 3: Initializing IM adapters
                updateStartupProgress(3, getString(R.string.gateway_step_init_im));

                // Step 4: Starting gateway
                updateStartupProgress(4, getString(R.string.gateway_step_starting));

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
                    HermesConfigManager.getInstance().recordSessionStart(HermesGatewayService.this);
                    clearRestartState();
                }

                cancelErrorNotification();

                // Step 5: Gateway running
                Notification notification = buildNotification(getString(R.string.gateway_step_running));
                startForeground(NOTIFICATION_ID, notification);
                mStartupStep = TOTAL_STARTUP_STEPS;

                mHandler.postDelayed(mHealthCheck, 30_000);
                Log.i(TAG, "Gateway process started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start gateway", e);
                updateNotification(getString(R.string.gateway_step_failed));
            }
        });
    }

    private void stopGateway() {
        if (mRunning) {
            HermesConfigManager.getInstance().recordSessionStop(HermesGatewayService.this);
        }
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

    private void updateStartupProgress(int step, String message) {
        mStartupStep = step;
        Notification notification = buildProgressNotification(step, TOTAL_STARTUP_STEPS, message);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildProgressNotification(int currentStep, int totalSteps, String message) {
        Intent openIntent = new Intent(this, TermuxActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HermesGatewayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean indeterminate = (currentStep >= totalSteps);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes Gateway (" + currentStep + "/" + totalSteps + ")")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_hermes)
                .setContentIntent(pi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setProgress(totalSteps, currentStep, indeterminate)
                .build();
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

        Intent restartIntent = new Intent(this, HermesGatewayService.class);
        restartIntent.setAction(ACTION_RESTART);
        PendingIntent restartPi = PendingIntent.getService(this, 2, restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes Gateway")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_hermes)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.notification_action_restart), restartPi)
                .addAction(0, getString(R.string.notification_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            int importance = getNotificationImportance();
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Hermes Gateway",
                    importance
            );
            channel.setDescription("Hermes gateway status");
            if (isNotificationSoundEnabled()) {
                channel.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                        new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .build());
            }
            if (isNotificationVibrateEnabled()) {
                channel.setVibrationPattern(new long[]{0, 300, 200, 300});
            }
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
                .setContentText("Gateway crashed and was restarted (attempt " + attempt + "/" + getMaxRestarts() + ")")
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
                .setContentText("Gateway crashed " + getMaxRestarts() + " times and could not recover")
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
        sInstance = null;
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

    /** Returns true if the gateway service is currently running. */
    public static boolean isRunning() {
        HermesGatewayService instance = sInstance;
        return instance != null && instance.mRunning;
    }

    /**
     * Returns a human-readable string representing the current gateway uptime,
     * e.g. "2h 15m 30s". Returns an empty string if the gateway is not running.
     */
    public static String getFormattedUptime() {
        HermesGatewayService instance = sInstance;
        if (instance == null || !instance.mRunning || instance.mProcessStartTime == 0) {
            return "";
        }
        long elapsed = System.currentTimeMillis() - instance.mProcessStartTime;
        return formatDuration(elapsed);
    }

    /** Formats a duration in milliseconds to a human-readable string like "2h 15m 30s". */
    public static String formatDuration(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        return sb.toString();
    }
}
