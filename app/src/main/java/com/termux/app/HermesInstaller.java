package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

public class HermesInstaller {

    private static final String LOG_TAG = "HermesInstaller";
    private static final String NOTIFICATION_CHANNEL_ID = "hermes_install";
    private static final int NOTIFICATION_ID = 2001;
    private static final int MAX_RETRIES = 3;

    static final String ACTION_RETRY_INSTALL = "com.bahtya.RETRY_INSTALL";
    static final String EXTRA_IS_RETRY = "is_retry";

    private static final String HERMES_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final String HERMES_BOOT_SCRIPT =
            TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH + "/hermes-gateway";
    private static final String HERMES_BASH_INIT_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-bash-init-deployed";
    private static final String HERMES_APT_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-apt-conf-deployed";
    private static final String HERMES_DPKG_CONF_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-dpkg-conf-deployed";
    private static final String HERMES_SHELL_PROFILE_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-shell-profile-deployed";
    private static final String HERMES_PATH_REWRITE_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-path-rewrite-deployed";
    private static final String HERMES_SYMLINK_FIX_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-symlinks-fixed";
    private static final String HERMES_DPKG_DB_FIX_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-dpkg-db-patched";
    private static final String HERMES_BASH_INIT_VERSION = "2";
    private static final String HERMES_APT_CONF_VERSION = "2";
    private static final String HERMES_DPKG_CONF_VERSION = "1";
    private static final String HERMES_SHELL_PROFILE_VERSION = "1";
    private static final String HERMES_PATH_REWRITE_VERSION = "2";
    private static final String HERMES_SYMLINK_FIX_VERSION = "2";
    private static final String HERMES_DPKG_DB_FIX_VERSION = "2";

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
     * get necessary fixes applied without needing a clean reinstall.
     */
    static void runUpgradeMigrations() {
        // Fix broken symlinks: bootstrap SYMLINKS.txt uses absolute paths with
        // com.termux which don't exist after the package rename. Earlier versions
        // didn't rewrite symlink targets, so existing installs need this fix.
        runMigration("Symlink fix", HERMES_SYMLINK_FIX_VERSION,
                HERMES_SYMLINK_FIX_MARKER_FILE, () ->
                        TermuxInstaller.fixPrefixSymlinks(TermuxConstants.TERMUX_PREFIX_DIR_PATH));

        // Versioned migrations (with target file existence check for apt/dpkg configs)
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        runMigration("Bash init", HERMES_BASH_INIT_VERSION,
                HERMES_BASH_INIT_MARKER_FILE, HermesInstaller::deployBashInit);
        runMigration("Apt conf", HERMES_APT_CONF_VERSION,
                HERMES_APT_CONF_MARKER_FILE, HermesInstaller::deployAptConf,
                prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf");
        runMigration("Dpkg conf", HERMES_DPKG_CONF_VERSION,
                HERMES_DPKG_CONF_MARKER_FILE, HermesInstaller::deployDpkgConf,
                prefix + "/etc/dpkg/dpkg.cfg.d/hermes-paths");
        runMigration("Dpkg db fix", HERMES_DPKG_DB_FIX_VERSION,
                HERMES_DPKG_DB_FIX_MARKER_FILE, () ->
                        patchDpkgDatabase(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        runMigration("Shell profile", HERMES_SHELL_PROFILE_VERSION,
                HERMES_SHELL_PROFILE_MARKER_FILE, HermesInstaller::deployShellProfile,
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.bashrc");
    }

    /**
     * Run migrations that require a Context (e.g., accessing APK native libs).
     * Called from TermuxApplication.onCreate() after runUpgradeMigrations().
     */
    static void runContextMigrations(Context context) {
        runMigration("Path rewrite", HERMES_PATH_REWRITE_VERSION,
                HERMES_PATH_REWRITE_MARKER_FILE, () -> deployPathRewrite(context),
                TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so");
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

    /**
     * Deploy apt.conf, dpkg.conf, LD_PRELOAD library, and patch the dpkg database.
     * Called from the install thread AFTER bootstrap is complete but BEFORE the
     * install script runs. Bootstrap extraction wipes $PREFIX, so configs deployed
     * during app.onCreate() no longer exist.
     */
    private static void deployInstallPrerequisites(Context context) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        try { deployAptConf(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt.conf deploy: " + e.getMessage());
        }
        try { deployDpkgConf(); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install dpkg.conf deploy: " + e.getMessage());
        }
        try { deployPathRewrite(context); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install path-rewrite deploy: " + e.getMessage());
        }
        try { patchDpkgDatabase(prefix); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install dpkg db patch: " + e.getMessage());
        }
        try { ensureAptDirectories(prefix); } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Pre-install apt dirs ensure: " + e.getMessage());
        }
    }

    private static void startInstallThread(Context context, boolean isRetry) {
        Thread t = new Thread(() -> {
            try {
                createNotificationChannel(context);
                showProgress(context, "Preparing installation...", 0);

                if (!isRetry) {
                    deployBootScript();
                }

                // Deploy critical configs BEFORE the install script runs.
                // Bootstrap extraction (TermuxInstaller line 137) deletes $PREFIX,
                // wiping any configs deployed by runUpgradeMigrations() during
                // app.onCreate(). Without these, dpkg/apt cannot find their
                // databases or config files and fail with error code (1).
                deployInstallPrerequisites(context);

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
                    markInstalled(context);
                    fixBinaryPermissions();
                    HermesConfigManager.reinitialize();
                    showSuccess(context, "Hermes Agent installed successfully");
                    Logger.logInfo(LOG_TAG, "Hermes installation complete.");
                } catch (Exception e) {
                    HermesInstallHelper.setState(context, HermesInstallHelper.InstallState.FAILED);
                    String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    HermesInstallHelper.setLastError(context, errorMsg);
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

    private static void markInstalled(Context context) throws Exception {
        try (FileOutputStream out = new FileOutputStream(HERMES_MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
        deployBashInit();
        deployAptConf();
        deployDpkgConf();
        deployShellProfile();
        deployPathRewrite(context);
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
                + "Dir::Log \"" + prefix + "/var/log/apt\";\n"
                + "Dpkg::Options { \"--admindir=" + prefix + "/var/lib/dpkg\"; };\n";

        try (FileOutputStream out = new FileOutputStream(confFile)) {
            out.write(content.getBytes("UTF-8"));
        }
        Os.chmod(confFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed apt.conf to " + confFile.getAbsolutePath());
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

    /**
     * Ensure apt's required working directories exist. Bootstrap extraction
     * wipes $PREFIX and the ZIP doesn't include empty cache directories,
     * causing apt to fail with "Archives directory …/partial is missing".
     */
    private static void ensureAptDirectories(String prefix) {
        String[] dirs = {
            "/var/cache/apt/archives/partial",
            "/var/lib/apt/lists/partial",
            "/var/log/apt"
        };
        for (String suffix : dirs) {
            File dir = new File(prefix + suffix);
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.logWarn(LOG_TAG, "Could not create apt directory: " + dir.getAbsolutePath());
            }
        }
    }

    /**
     * Patch the dpkg database files to replace /data/data/com.termux paths.
     * The bootstrap ZIP's var/lib/dpkg/ directory contains:
     * - info/*.list files with com.termux file paths
     * - info/*.postinst/prerm scripts with com.termux shebangs and paths
     * - status file with package metadata
     * These were NOT patched by earlier CI scripts, causing dpkg to fail
     * with error code (1) when running maintainer scripts or checking files.
     */
    private static void patchDpkgDatabase(String prefixPath) {
        final String oldPrefix = "/data/data/com.termux";
        final String newPrefix = "/data/data/com.bahtya";
        int patched = 0;

        File dpkgInfo = new File(prefixPath, "var/lib/dpkg/info");
        if (dpkgInfo.isDirectory()) {
            File[] files = dpkgInfo.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                }
            }
        }

        File statusFile = new File(prefixPath, "var/lib/dpkg/status");
        if (statusFile.exists()) {
            if (patchTextFileSafe(statusFile, oldPrefix, newPrefix)) patched++;
        }

        // Also patch the alternatives directory if present
        File altDir = new File(prefixPath, "var/lib/dpkg/alternatives");
        if (altDir.isDirectory()) {
            File[] files = altDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                }
            }
        }

        // Patch apt's own data directories
        File aptListsDir = new File(prefixPath, "var/lib/apt/lists");
        if (aptListsDir.isDirectory()) {
            File[] files = aptListsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (patchTextFileSafe(f, oldPrefix, newPrefix)) patched++;
                }
            }
        }

        Logger.logInfo(LOG_TAG, "Dpkg database patching: " + patched + " files patched");
    }

    private static boolean patchTextFileSafe(File file, String oldStr, String newStr) {
        try {
            byte[] raw = readFileBytes(file);
            if (raw == null) return false;
            String content = new String(raw, "UTF-8");
            if (!content.contains(oldStr)) return false;
            content = content.replace(oldStr, newStr);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes("UTF-8"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readFileBytes(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Deploy the LD_PRELOAD path rewrite library from the APK's native libs
     * to $PREFIX/lib/libpath_rewrite.so. This library intercepts all
     * filesystem calls and rewrites /data/data/com.termux/ paths to
     * /data/data/com.bahtya/, fixing ALL binaries with compiled-in
     * old paths at once.
     */
    private static void deployPathRewrite(Context context) throws Exception {
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File srcFile = new File(nativeLibDir, "libpath_rewrite.so");
        File dstFile = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "libpath_rewrite.so");

        if (!srcFile.exists()) {
            throw new Exception("libpath_rewrite.so not found in " + nativeLibDir);
        }

        File libDir = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new Exception("Failed to create " + libDir.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(srcFile);
             FileOutputStream fos = new FileOutputStream(dstFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
        }
        Os.chmod(dstFile.getAbsolutePath(), 0644);
        Logger.logInfo(LOG_TAG, "Deployed path rewrite lib to " + dstFile.getAbsolutePath());
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
