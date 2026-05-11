package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.system.Os;

import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.hermes.HermesConfigManager;
import com.termux.app.hermes.HermesInstallHelper;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class HermesInstaller {

    private static final String LOG_TAG = "HermesInstaller";
    private static final String NOTIFICATION_CHANNEL_ID = "hermes_install";
    private static final int NOTIFICATION_ID = 2001;
    private static final int MAX_RETRIES = 3;

    static final String ACTION_RETRY_INSTALL = "com.hermes.termux.RETRY_INSTALL";
    static final String EXTRA_IS_RETRY = "is_retry";

    private static final String HERMES_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final String HERMES_BOOT_SCRIPT =
            TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH + "/hermes-gateway";

    private HermesInstaller() {}

    static void installIfNeeded(Context context) {
        if (new File(HERMES_MARKER_FILE).exists()) {
            Logger.logInfo(LOG_TAG, "Hermes already installed, skipping.");
            return;
        }
        startInstallThread(context, false);
    }

    public static void retryInstall(Context context) {
        startInstallThread(context, true);
    }

    private static void startInstallThread(Context context, boolean isRetry) {
        Thread t = new Thread(() -> {
            try {
                createNotificationChannel(context);
                showProgress(context, "Preparing installation...", 0);

                if (!isRetry) {
                    deployBootScript();
                }

                try {
                    HermesInstallHelper.executeInstall(context, MAX_RETRIES, new HermesInstallHelper.ProgressCallback() {
                        @Override
                        public void onStatus(String message) {
                            showProgress(context, message, 30);
                        }
                        @Override
                        public boolean isCancelled() {
                            return Thread.currentThread().isInterrupted();
                        }
                    });
                    markInstalled();
                    HermesConfigManager.reinitialize();
                    showSuccess(context, "Hermes Agent installed successfully");
                    Logger.logInfo(LOG_TAG, "Hermes installation complete.");
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    showError(context, "Installation failed: " + errorMsg);
                    Logger.logErrorExtended(LOG_TAG, "Hermes installation failed:\n" + errorMsg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Hermes installation failed:\n" + e.getMessage());
                showError(context, "Installation failed: " + e.getMessage());
            }
        }, "HermesInstaller");
        t.setDaemon(true);
        t.start();
    }

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "Hermes Installation",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
    }

    private static void showProgress(Context context, String message, int percent) {
        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Installing Hermes Agent")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notify(context, notification);
    }

    private static void showSuccess(Context context, String message) {
        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Hermes Agent")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notify(context, notification);
    }

    private static void showError(Context context, String message) {
        Intent retryIntent = new Intent(ACTION_RETRY_INSTALL);
        retryIntent.setPackage(context.getPackageName());
        retryIntent.putExtra(EXTRA_IS_RETRY, true);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Hermes Installation Failed")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_service_notification)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .addAction(R.drawable.ic_service_notification, "Retry", pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        notify(context, notification);
    }

    private static void notify(Context context, Notification notification) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification);
    }

    private static void deployBootScript() throws Exception {
        File bootDir = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR;
        FileUtils.createDirectoryFile(bootDir.getAbsolutePath());

        String script = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n"
                + "# Auto-start Hermes gateway on boot\n"
                + "if command -v hermes >/dev/null 2>&1; then\n"
                + "    hermes gateway run &\n"
                + "fi\n";

        File scriptFile = new File(HERMES_BOOT_SCRIPT);
        try (FileOutputStream out = new FileOutputStream(scriptFile)) {
            out.write(script.getBytes("UTF-8"));
        }
        Os.chmod(scriptFile.getAbsolutePath(), 0700);
        Logger.logInfo(LOG_TAG, "Deployed boot script to " + HERMES_BOOT_SCRIPT);
    }

    private static void markInstalled() throws Exception {
        try (FileOutputStream out = new FileOutputStream(HERMES_MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
        deployShellProfile();
    }

    private static void deployShellProfile() throws Exception {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File bashrc = new File(home, ".bashrc");
        String hermesBlock = "\n# Hermes Terminal Configuration\n"
                + "export USER=hermes\n"
                + "export LOGNAME=hermes\n"
                + "export PS1='\\[\\e[1;32m\\]hermes@hermes\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n";

        if (bashrc.exists()) {
            String content = readFile(bashrc);
            if (!content.contains("Hermes Terminal Configuration")) {
                try (FileOutputStream out = new FileOutputStream(bashrc, true)) {
                    out.write(hermesBlock.getBytes("UTF-8"));
                }
                Logger.logInfo(LOG_TAG, "Appended Hermes shell profile to .bashrc");
            }
        } else {
            try (FileOutputStream out = new FileOutputStream(bashrc)) {
                out.write(hermesBlock.getBytes("UTF-8"));
            }
            Os.chmod(bashrc.getAbsolutePath(), 0644);
            Logger.logInfo(LOG_TAG, "Created .bashrc with Hermes shell profile");
        }
    }

    private static String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public static class RetryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_RETRY_INSTALL.equals(intent.getAction())) {
                // Delete marker to allow re-install
                new File(HERMES_MARKER_FILE).delete();
                retryInstall(context.getApplicationContext());
            }
        }
    }
}
