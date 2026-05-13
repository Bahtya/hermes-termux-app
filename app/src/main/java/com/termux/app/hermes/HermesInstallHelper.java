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
    private static final String KEY_INSTALL_ERROR = "install_error";

    private static final String MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";

    static final String INSTALL_URL_DIRECT =
            "https://hermes-agent.nousresearch.com/install.sh";

    static final String HERMES_AGENT_COMMIT = "486b692ddd801f8f665d3fff023149fb1cb6509e";

    static final String INSTALL_URL_GITHUB_RAW =
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/" + HERMES_AGENT_COMMIT + "/scripts/install.sh";

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
        setLastError(context, null);
    }

    public static void setLastError(Context context, String error) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_INSTALL_ERROR, error != null ? error : "").apply();
    }

    public static String getLastError(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_INSTALL_ERROR, "");
    }

    // =========================================================================
    // Progress callback
    // =========================================================================

    public interface ProgressCallback {
        void onStatus(String message);
        boolean isCancelled();
        /** Called for each line of output from the install script. */
        default void onOutput(String line) {}
    }

    /** Runs after Phase 0 (bootstrap ready) but before download attempts. */
    public interface PostBootstrapHook {
        void onBootstrapReady() throws Exception;
    }

    // =========================================================================
    // Install execution
    // =========================================================================

    /**
     * Run the install script with automatic mirror fallback.
     * Phase 0: wait for bootstrap, then run postBootstrapHook.
     * Phase 1: direct connection (up to maxDirectRetries attempts, 5s delay).
     * Phase 2: each mirror in MIRROR_PREFIXES (1 attempt per mirror).
     */
    public static void executeInstall(Context context, int maxDirectRetries,
            ProgressCallback callback, PostBootstrapHook postBootstrap) throws Exception {
        StringBuilder errorLog = new StringBuilder();

        // Phase 0: wait for Termux bootstrap to finish
        setState(context, InstallState.BOOTSTRAPPING);
        ensureBashReady(context, callback);

        // Phase 0.5: wait for any apt/dpkg processes to finish and clean stale locks
        waitForAptReady();

        // Deploy apt/dpkg configs AFTER bootstrap is ready.
        // Bootstrap extraction wipes $PREFIX, so configs deployed earlier are gone.
        if (postBootstrap != null) {
            postBootstrap.onBootstrapReady();
        }

        // Phase 1: direct attempts
        setState(context, InstallState.DOWNLOADING);
        for (int attempt = 1; attempt <= maxDirectRetries; attempt++) {
            if (callback != null && callback.isCancelled()) return;
            try {
                if (callback != null) {
                    callback.onStatus(context.getString(R.string.install_direct_attempt, attempt, maxDirectRetries));
                }
                runShellCommand(buildInstallCommand(false, null), callback);
                setLastError(context, null);
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
                runShellCommand(buildInstallCommand(true, mirror), callback);
                setLastError(context, null);
                setState(context, InstallState.INSTALLED);
                return;
            } catch (Exception e) {
                errorLog.append("Mirror (").append(mirror).append("): ")
                        .append(e.getMessage()).append("\n");
                Logger.logWarn(LOG_TAG, "Mirror " + mirror + " failed: " + e.getMessage());
            }
        }

        String errorMsg = "All download methods failed\n" + errorLog;
        setLastError(context, errorMsg);
        setState(context, InstallState.FAILED);
        throw new RuntimeException(errorMsg);
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
                    String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
                    if (new java.io.File(pathRewriteLib).exists()) {
                        pb.environment().put("LD_PRELOAD", pathRewriteLib);
                    }
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
            Logger.logInfo(LOG_TAG, "Bootstrap not ready, waiting... (" + (i + 1) + "/" + maxWaitAttempts + ")");
            Thread.sleep(3000);
        }
        String bootstrapError = "Termux bootstrap packages are not ready after 60 seconds";
        setLastError(context, bootstrapError);
        setState(context, InstallState.FAILED);
        throw new RuntimeException(bootstrapError);
    }

    /**
     * Wait for any running apt/dpkg processes to finish and remove stale
     * lock files. Bootstrap extraction or app initialization may trigger
     * apt commands that hold locks, causing the install script's
     * "pkg install python" to fail with lock errors.
     */
    private static void waitForAptReady() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        if (!new File(bashPath).exists()) return;

        // Shell snippet: wait up to 30s for apt/dpkg processes to exit.
        // Only remove lock files if no apt/dpkg process is still running,
        // to avoid corrupting a running transaction.
        String cmd = "_cleaned=false; "
            + "for _i in $(seq 1 30); do "
            + "_r=false; "
            + "for _p in /proc/[0-9]*/cmdline; do "
            + "[ -r \"$_p\" ] && { cat \"$_p\" 2>/dev/null | tr '\\0' ' ' "
            + "| grep -qE '/apt|/dpkg' && _r=true && break; }; "
            + "done; "
            + "if [ \"$_r\" = false ]; then "
            + "rm -f " + prefix + "/var/lib/apt/lists/lock "
            + prefix + "/var/cache/apt/archives/lock "
            + prefix + "/var/lib/dpkg/lock-frontend "
            + prefix + "/var/lib/dpkg/lock; "
            + "_cleaned=true; break; "
            + "fi; "
            + "sleep 1; "
            + "done; "
            + "[ \"$_cleaned\" = true ]";

        try {
            runShellCommand(cmd, null);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "apt readiness check failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Build the shell command for downloading and executing the install script.
     */
    static String buildInstallCommand(boolean useMirror, String mirrorPrefix) {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

        String curlTimeout = "--connect-timeout 30 --max-time 300";

        if (!useMirror) {
            return "curl -fsSL " + curlTimeout + " " + INSTALL_URL_DIRECT + " | " + bashPath;
        }

        String mirrorScriptUrl = mirrorPrefix + INSTALL_URL_GITHUB_RAW;
        String mirrorRepoUrl = mirrorPrefix + GITHUB_REPO_URL;
        return "curl -fsSL " + curlTimeout + " " + mirrorScriptUrl
                + " | sed 's|" + GITHUB_REPO_URL + "|" + mirrorRepoUrl + "|g'"
                + " | " + bashPath;
    }

    /**
     * Execute a bash command in the Termux environment.
     * Streams each line of output to callback.onOutput() in real time.
     */
    static void runShellCommand(String bashCommand, ProgressCallback callback) throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";

        if (!new File(bashPath).exists() || !new File(curlPath).exists()) {
            throw new RuntimeException("bash or curl not available yet");
        }

        ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", bashCommand);
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + ":/system/bin:/system/xbin");
        pb.environment().put("PREFIX", prefix);
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        pb.environment().put("TERMUX_VERSION", com.termux.BuildConfig.VERSION_NAME);
        pb.environment().put("TERMINFO", prefix + "/share/terminfo");

        // LD_PRELOAD rewrites /data/data/com.termux/ → /data/data/com.bahtya/
        // at runtime. Without it, dpkg/apt cannot find their config files or
        // admindir (compiled-in as com.termux paths that don't exist).
        String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
        if (new File(pathRewriteLib).exists()) {
            pb.environment().put("LD_PRELOAD", pathRewriteLib);
        }

        // APT_CONFIG overrides compiled-in Dir paths so apt can find its
        // sources.list and method binaries under the renamed package path.
        String aptConfFile = prefix + "/etc/apt/apt.conf.d/99hermes-paths.conf";
        if (new File(aptConfFile).exists()) {
            pb.environment().put("APT_CONFIG", aptConfFile);
        }

        pb.redirectErrorStream(true);

        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (callback != null) callback.onOutput(line);
            }
        }

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Install script exited with code " + exit
                    + (output.length() > 0 ? "\n" + output : ""));
        }
    }
}
