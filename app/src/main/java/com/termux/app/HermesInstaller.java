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
    private static final String HERMES_INSTALL_URL =
            "https://hermes-agent.nousresearch.com/install.sh";

    private HermesInstaller() {}

    static void installIfNeeded(Context context) {
        if (new File(HERMES_MARKER_FILE).exists()) {
            Logger.logInfo(LOG_TAG, "Hermes already installed, skipping.");
            return;
        }
        startInstallThread(context, false);
    }

    static void retryInstall(Context context) {
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

                boolean success = false;
                Exception lastError = null;

                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        showProgress(context, "Downloading Hermes Agent... (attempt " + attempt + "/" + MAX_RETRIES + ")", 30);
                        runInstallScript(attempt);
                        success = true;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        Logger.logWarn(LOG_TAG, "Install attempt " + attempt + " failed: " + e.getMessage());
                        if (attempt < MAX_RETRIES) {
                            showProgress(context, "Retrying in 5s... (" + attempt + "/" + MAX_RETRIES + ")", 30);
                            Thread.sleep(5000);
                        }
                    }
                }

                if (success) {
                    markInstalled();
                    showSuccess(context, "Hermes Agent installed successfully");
                    Logger.logInfo(LOG_TAG, "Hermes installation complete.");
                } else {
                    String errorMsg = lastError != null ? lastError.getMessage() : "Unknown error";
                    showError(context, "Installation failed: " + errorMsg);
                    Logger.logErrorExtended(LOG_TAG, "Hermes installation failed after " + MAX_RETRIES + " attempts:\n" + errorMsg);
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

    private static void runInstallScript(int attempt) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";

        if (!new File(bashPath).exists() || !new File(curlPath).exists()) {
            throw new RuntimeException("bash or curl not available yet");
        }

        ProcessBuilder pb = new ProcessBuilder(
                bashPath, "-c",
                "curl -fsSL " + HERMES_INSTALL_URL + " | " + bashPath
        );
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + ":/system/bin:/system/xbin");
        pb.redirectErrorStream(true);

        Process p = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Install script exited with code " + exit
                    + (output.length() > 0 ? "\n" + output : ""));
        }
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
