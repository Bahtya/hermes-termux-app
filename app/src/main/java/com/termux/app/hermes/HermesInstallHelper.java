package com.termux.app.hermes;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Shared installation logic with automatic mirror fallback for regions
 * where direct GitHub access is unreliable (e.g. mainland China).
 */
public class HermesInstallHelper {

    private static final String LOG_TAG = "HermesInstallHelper";
    private static final String PREFS_NAME = "hermes_install_state";
    private static final String KEY_INSTALL_STATE = "install_state";

    private static final String MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";

    static final String INSTALL_URL_DIRECT =
            "https://hermes-agent.nousresearch.com/install.sh";

    static final String INSTALL_URL_GITHUB_RAW =
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh";

    static final String GITHUB_REPO_URL =
            "https://github.com/NousResearch/hermes-agent.git";

    static final String[] MIRROR_PREFIXES = {
            "https://ghfast.top/",
    };

    public enum InstallState {
        NOT_INSTALLED,
        BOOTSTRAPPING,
        DOWNLOADING,
        INSTALLING,
        INSTALLED,
        FAILED
    }

    private HermesInstallHelper() {}

    // =========================================================================
    // State persistence
    // =========================================================================

    public static void setState(Context context, InstallState state) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_INSTALL_STATE, state.name()).apply();
        Logger.logInfo(LOG_TAG, "Install state: " + state.name());
    }

    public static InstallState getState(Context context) {
        String name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_INSTALL_STATE, null);
        if (name != null) {
            try { return InstallState.valueOf(name); } catch (IllegalArgumentException ignored) {}
        }
        return getCurrentState(context);
    }

    /** Determine actual state from filesystem (marker file + bash availability). */
    public static InstallState getCurrentState(Context context) {
        if (new File(MARKER_FILE).exists()) {
            return InstallState.INSTALLED;
        }
        return InstallState.NOT_INSTALLED;
    }

    /** Delete marker file and reset state to allow reinstallation. */
    public static void resetInstall(Context context) {
        new File(MARKER_FILE).delete();
        setState(context, InstallState.NOT_INSTALLED);
    }

    // =========================================================================
    // Progress callback
    // =========================================================================

    public interface ProgressCallback {
        void onStatus(String message);
        boolean isCancelled();
    }

    // =========================================================================
    // Install execution
    // =========================================================================

    /**
     * Run the install script with automatic mirror fallback.
     * Phase 0: wait for bootstrap.
     * Phase 1: direct connection (up to maxDirectRetries attempts, 5s delay).
     * Phase 2: each mirror in MIRROR_PREFIXES (1 attempt per mirror).
     */
    public static void executeInstall(Context context, int maxDirectRetries, ProgressCallback callback) throws Exception {
        StringBuilder errorLog = new StringBuilder();

        // Phase 0: wait for Termux bootstrap to finish
        setState(context, InstallState.BOOTSTRAPPING);
        ensureBashReady(context, callback);

        // Phase 1: direct attempts
        setState(context, InstallState.INSTALLING);
        for (int attempt = 1; attempt <= maxDirectRetries; attempt++) {
            if (callback != null && callback.isCancelled()) return;
            try {
                if (callback != null) {
                    callback.onStatus(context.getString(R.string.install_direct_attempt, attempt, maxDirectRetries));
                }
                runShellCommand(buildInstallCommand(false, null));
                setState(context, InstallState.INSTALLED);
                return;
            } catch (Exception e) {
                errorLog.append("Direct attempt ").append(attempt).append(": ")
                        .append(e.getMessage()).append("\n");
                Logger.logWarn(LOG_TAG, "Direct install attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < maxDirectRetries) {
                    if (callback != null) {
                        callback.onStatus(context.getString(R.string.install_retrying, attempt, maxDirectRetries));
                    }
                    Thread.sleep(5000);
                }
            }
        }

        // Phase 2: mirror fallback
        for (String mirror : MIRROR_PREFIXES) {
            if (callback != null && callback.isCancelled()) return;
            try {
                if (callback != null) {
                    callback.onStatus(context.getString(R.string.install_fallback_mirror));
                }
                Logger.logInfo(LOG_TAG, "Falling back to mirror: " + mirror);
                runShellCommand(buildInstallCommand(true, mirror));
                setState(context, InstallState.INSTALLED);
                return;
            } catch (Exception e) {
                errorLog.append("Mirror (").append(mirror).append("): ")
                        .append(e.getMessage()).append("\n");
                Logger.logWarn(LOG_TAG, "Mirror " + mirror + " failed: " + e.getMessage());
            }
        }

        setState(context, InstallState.FAILED);
        throw new RuntimeException("All download methods failed\n" + errorLog);
    }

    /**
     * Wait until bash can actually execute (bootstrap packages fully installed).
     * Retries every 3 seconds for up to 60 seconds.
     */
    private static void ensureBashReady(Context context, ProgressCallback callback) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        int maxWaitAttempts = 20;

        for (int i = 0; i < maxWaitAttempts; i++) {
            if (callback != null && callback.isCancelled()) return;
            if (new File(bashPath).exists()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", "echo ok");
                    pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    // Drain output to avoid blocking
                    try (java.io.InputStream is = p.getInputStream()) {
                        byte[] buf = new byte[64];
                        while (is.read(buf) != -1) {}
                    }
                    int exit = p.waitFor();
                    if (exit == 0) return;
                } catch (Exception ignored) {}
            }
            if (callback != null) {
                callback.onStatus(context.getString(R.string.install_waiting_bootstrap, (i + 1), maxWaitAttempts));
            }
            if (callback != null) {
                callback.onStatus(context.getString(R.string.install_waiting_bootstrap, (i + 1), maxWaitAttempts));
            }
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting... (" + (i + 1) + "/" + maxWaitAttempts + ")");
            Thread.sleep(3000);
        }
        setState(context, InstallState.FAILED);
        throw new RuntimeException("Termux bootstrap packages are not ready after 60 seconds");
    }

    /**
     * Build the shell command for downloading and executing the install script.
     */
    static String buildInstallCommand(boolean useMirror, String mirrorPrefix) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        if (!useMirror) {
            return "curl -fsSL " + INSTALL_URL_DIRECT + " | " + bashPath;
        }

        String mirrorScriptUrl = mirrorPrefix + INSTALL_URL_GITHUB_RAW;
        String mirrorRepoUrl = mirrorPrefix + GITHUB_REPO_URL;
        return "curl -fsSL " + mirrorScriptUrl
                + " | sed 's|" + GITHUB_REPO_URL + "|" + mirrorRepoUrl + "|g'"
                + " | " + bashPath;
    }

    /**
     * Execute a bash command in the Termux environment.
     */
    static void runShellCommand(String bashCommand) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";

        if (!new File(bashPath).exists() || !new File(curlPath).exists()) {
            throw new RuntimeException("bash or curl not available yet");
        }

        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", bashCommand);
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + ":/system/bin:/system/xbin");
        pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        pb.environment().put("TERMUX_VERSION", com.termux.BuildConfig.VERSION_NAME);
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
}
