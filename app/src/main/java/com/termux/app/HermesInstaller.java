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
    private static final String HERMES_PATCH_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-paths-patched";
    private static final String HERMES_REPATCH_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-needs-repatch";
    private static final String HERMES_BASH_INIT_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-bash-init-deployed";
    private static final String HERMES_APT_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-apt-conf-deployed";
    private static final String HERMES_APT_HOOK_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-apt-hook-deployed";
    private static final String HERMES_DPKG_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-dpkg-conf-deployed";
    private static final String HERMES_SHELL_PROFILE_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-shell-profile-deployed";
    private static final String HERMES_BASH_INIT_VERSION = "2";
    private static final String HERMES_APT_CONF_VERSION = "1";
    private static final String HERMES_APT_HOOK_VERSION = "1";
    private static final String HERMES_DPKG_CONF_VERSION = "1";
    private static final String HERMES_SHELL_PROFILE_VERSION = "1";

    private HermesInstaller() {}

    static void installIfNeeded(Context context) {
        if (new File(HERMES_MARKER_FILE).exists()) {
            if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "hermes").exists()) {
                Logger.logWarn(LOG_TAG, "hermes-installed marker exists but binary missing, retrying install");
                new File(HERMES_MARKER_FILE).delete();
            } else {
                Logger.logInfo(LOG_TAG, "Hermes already installed, skipping.");
                return;
            }
        }
        startInstallThread(context, false);
    }

    /**
     * Run upgrade migrations for existing installations. Called on every app start
     * from TermuxApplication so that users who upgrade from a previous version
     * get their bootstrap binaries patched without needing a clean reinstall.
     */
    static void runUpgradeMigrations() {
        // Migration 1: Patch bootstrap binary paths (non-versioned, runs once)
        if (!new File(HERMES_PATCH_MARKER_FILE).exists()) {
            if (new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").exists()) {
                Logger.logInfo(LOG_TAG, "Running upgrade migration: patching bootstrap paths");
                TermuxInstaller.patchBootstrapPaths(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            }
            try {
                try (FileOutputStream out = new FileOutputStream(HERMES_PATCH_MARKER_FILE)) {
                    out.write("1\n".getBytes("UTF-8"));
                }
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Failed to write patch marker: " + e.getMessage());
            }
        }

        // Re-patch if apt/dpkg hook signaled that packages were updated
        if (new File(HERMES_REPATCH_MARKER_FILE).exists()) {
            Logger.logInfo(LOG_TAG, "Re-patching binaries after package update");
            TermuxInstaller.patchBootstrapPaths(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            new File(HERMES_REPATCH_MARKER_FILE).delete();
        }

        // Versioned migrations (with target file existence check for apt/dpkg configs)
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        runMigration("Bash init", HERMES_BASH_INIT_VERSION,
                HERMES_BASH_INIT_MARKER_FILE, HermesInstaller::deployBashInit);
        runMigration("Apt conf", HERMES_APT_CONF_VERSION,
                HERMES_APT_CONF_MARKER_FILE, HermesInstaller::deployAptConf,
                prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf");
        runMigration("Apt hook", HERMES_APT_HOOK_VERSION,
                HERMES_APT_HOOK_MARKER_FILE, HermesInstaller::deployAptHook,
                prefix + "/libexec/hermes-patch-paths");
        runMigration("Dpkg conf", HERMES_DPKG_CONF_VERSION,
                HERMES_DPKG_CONF_MARKER_FILE, HermesInstaller::deployDpkgConf,
                prefix + "/etc/dpkg/dpkg.cfg.d/hermes-paths");
        runMigration("Shell profile", HERMES_SHELL_PROFILE_VERSION,
                HERMES_SHELL_PROFILE_MARKER_FILE, HermesInstaller::deployShellProfile);
    }

    private static void runMigration(String name, String version,
            String markerPath, ThrowingRunnable deployAction) {
        runMigration(name, version, markerPath, deployAction, null);
    }

    private static void runMigration(String name, String version,
            String markerPath, ThrowingRunnable deployAction, String targetFilePath) {
        boolean needsDeploy = true;
        File marker = new File(markerPath);
        if (marker.exists()) {
            try {
                String deployedVersion = readFile(marker).trim();
                if (version.equals(deployedVersion)) {
                    needsDeploy = false;
                    // If target file was deleted (e.g. by apt upgrade), redeploy
                    if (targetFilePath != null && !new File(targetFilePath).exists()) {
                        needsDeploy = true;
                        Logger.logInfo(LOG_TAG, name + " target file missing, redeploying");
                    }
                }
            } catch (Exception e) {
                // Marker file unreadable - re-deploy
            }
        }
        if (needsDeploy) {
            try {
                deployAction.run();
                try (FileOutputStream out = new FileOutputStream(markerPath)) {
                    out.write((version + "\n").getBytes("UTF-8"));
                }
                Logger.logInfo(LOG_TAG, name + " migration complete (v" + version + ")");
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Failed " + name + " migration: " + e.getMessage());
            }
        }
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
                    fixBinaryPermissions();
                    HermesConfigManager.reinitialize();
                    showSuccess(context, "Hermes Agent installed successfully");
                    Logger.logInfo(LOG_TAG, "Hermes installation complete.");
                } catch (Exception e) {
                    HermesInstallHelper.setState(context, HermesInstallHelper.InstallState.FAILED);
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
        deployBashInit();
        deployAptConf();
        deployAptHook();
        deployDpkgConf();
        deployShellProfile();
    }

    /**
     * Deploy the .hermes_bash_init file that serves as bash's --rcfile target.
     * This bypasses the compiled-in /data/data/com.termux/.../bash.bashrc path
     * which causes "Permission denied" on forked packages where the package name
     * differs. The init file sources the system bash.bashrc and profile from the
     * correct $PREFIX path, then sources the user's .bashrc.
     */
    private static void deployBashInit() throws Exception {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        File initFile = new File(home, ".hermes_bash_init");

        String content = "# Hermes bash init - sourced via --rcfile to bypass compiled-in bash.bashrc path\n"
                + "if [ -f \"$PREFIX/etc/bash.bashrc\" ]; then\n"
                + "    . \"$PREFIX/etc/bash.bashrc\"\n"
                + "fi\n"
                + "if [ -f \"$HOME/.bashrc\" ]; then\n"
                + "    . \"$HOME/.bashrc\"\n"
                + "fi\n";

        try (FileOutputStream out = new FileOutputStream(initFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(initFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed bash init file to " + initFile.getAbsolutePath());
    }

    /**
     * Deploy apt.conf with explicit directory paths to override compiled-in defaults.
     * The upstream apt binary has paths like Dir::Etc compiled in as
     * /data/data/com.termux/files/usr/etc/apt. Binary patching can fail silently
     * when there isn't enough null-byte padding after the old string, so this
     * config file forces apt to use the correct paths regardless of patching result.
     */
    private static void deployAptConf() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File aptConfDir = new File(prefix, "etc/apt/apt.conf.d");
        if (!aptConfDir.exists() && !aptConfDir.mkdirs()) {
            throw new Exception("Failed to create " + aptConfDir.getAbsolutePath());
        }

        File confFile = new File(aptConfDir, "99hermes-paths.conf");

        String content = "// Hermes: override compiled-in directory paths for renamed package\n"
                + "Dir \"" + prefix + "\";\n"
                + "Dir::State \"" + prefix + "/var/lib/apt\";\n"
                + "Dir::State::status \"" + prefix + "/var/lib/dpkg/status\";\n"
                + "Dir::Cache \"" + prefix + "/var/cache/apt\";\n"
                + "Dir::Etc \"" + prefix + "/etc/apt\";\n"
                + "Dir::Bin::methods \"" + prefix + "/lib/apt/methods\";\n"
                + "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n"
                + "Dir::Log \"" + prefix + "/var/log/apt\";\n";

        try (FileOutputStream out = new FileOutputStream(confFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(confFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed apt.conf to " + confFile.getAbsolutePath());
    }

    /**
     * Deploy apt/dpkg post-invoke hooks that create a marker file after
     * every package install/upgrade/remove. On next app start, the marker
     * triggers re-patching of binaries so that newly installed packages
     * from the upstream Termux repo (which have com.termux paths) get patched.
     */
    private static void deployAptHook() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;

        // Deploy the hook script to libexec
        File libexecDir = new File(prefix, "libexec");
        if (!libexecDir.exists() && !libexecDir.mkdirs()) {
            throw new Exception("Failed to create " + libexecDir.getAbsolutePath());
        }

        File hookScript = new File(libexecDir, "hermes-patch-paths");
        String markerFile = HERMES_REPATCH_MARKER_FILE;

        String script = "#!" + prefix + "/bin/sh\n"
                + "# Hermes: signal that binaries need re-patching after package operations.\n"
                + "# On next app start, the Java migration will re-run patchBootstrapPaths().\n"
                + "touch " + markerFile + "\n";

        try (FileOutputStream out = new FileOutputStream(hookScript)) {
            out.write(script.getBytes("UTF-8"));
        }
        Os.chmod(hookScript.getAbsolutePath(), 0755);
        Logger.logInfo(LOG_TAG, "Deployed apt hook script to " + hookScript.getAbsolutePath());

        // Deploy the apt.conf.d hook configuration
        File aptConfDir = new File(prefix, "etc/apt/apt.conf.d");
        if (!aptConfDir.exists() && !aptConfDir.mkdirs()) {
            throw new Exception("Failed to create " + aptConfDir.getAbsolutePath());
        }

        File hookConf = new File(aptConfDir, "98hermes-post-invoke");
        String hookContent = "// Hermes: trigger re-patching after package operations\n"
                + "DPkg::Post-Invoke { \"" + hookScript.getAbsolutePath() + "\"; };\n"
                + "APT::Update::Post-Invoke-Success { \"" + hookScript.getAbsolutePath() + "\"; };\n";

        try (FileOutputStream out = new FileOutputStream(hookConf)) {
            out.write(hookContent.getBytes("UTF-8"));
        }
        Os.chmod(hookConf.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed apt hook config to " + hookConf.getAbsolutePath());
    }

    /**
     * Deploy dpkg configuration to override compiled-in admindir path.
     * dpkg has /data/data/com.termux/files/usr/var/lib/dpkg baked in,
     * and binary patching may fail for the same null-padding reasons as apt.
     */
    private static void deployDpkgConf() throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File dpkgCfgDir = new File(prefix, "etc/dpkg/dpkg.cfg.d");
        if (!dpkgCfgDir.exists() && !dpkgCfgDir.mkdirs()) {
            throw new Exception("Failed to create " + dpkgCfgDir.getAbsolutePath());
        }

        File confFile = new File(dpkgCfgDir, "hermes-paths");

        String content = "# Hermes: override compiled-in dpkg directory paths\n"
                + "admindir " + prefix + "/var/lib/dpkg\n";

        try (FileOutputStream out = new FileOutputStream(confFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(confFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed dpkg.conf to " + confFile.getAbsolutePath());
    }

    private static void deployShellProfile() throws Exception {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File bashrc = new File(home, ".bashrc");
        String hermesBlock = "\n# Hermes Terminal Configuration\n"
                + "if [ -f \"$PREFIX/etc/profile\" ]; then\n"
                + "    . \"$PREFIX/etc/profile\"\n"
                + "fi\n"
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

    /** Fix execute permissions on critical Termux binaries after hermes-agent install. */
    private static void fixBinaryPermissions() {
        String binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String[] binaries = {"bash", "sh", "login", "cat", "chmod", "cp", "ls", "mkdir", "rm",
                "hermes", "git", "curl", "python", "pip", "sed", "grep", "tar", "env"};
        for (String name : binaries) {
            File bin = new File(binDir, name);
            if (bin.exists()) {
                try {
                    Os.chmod(bin.getAbsolutePath(), 0755);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Could not chmod " + bin.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
        Logger.logInfo(LOG_TAG, "Binary permissions fixed");
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
