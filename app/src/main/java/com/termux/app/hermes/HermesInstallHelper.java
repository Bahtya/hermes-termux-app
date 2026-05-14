package com.termux.app.hermes;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final AtomicBoolean sInstallRunning = new AtomicBoolean(false);
    private static final StringBuilder sOutputBuffer = new StringBuilder();
    private static final int MAX_BUFFER_SIZE = 50000;

    public static boolean isInstallRunning() {
        return sInstallRunning.get();
    }

    public static String getOutputBuffer() {
        synchronized (sOutputBuffer) {
            return sOutputBuffer.toString();
        }
    }

    public static void clearOutputBuffer() {
        synchronized (sOutputBuffer) {
            sOutputBuffer.setLength(0);
        }
    }

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
     * Phase 1: local inline install script (clones repo, builds psutil, installs hermes).
     * Phase 2: each mirror in MIRROR_PREFIXES (1 attempt per mirror, last resort).
     */
    public static void executeInstall(Context context,
            ProgressCallback callback, PostBootstrapHook postBootstrap) throws Exception {
        if (!sInstallRunning.compareAndSet(false, true)) {
            Logger.logWarn(LOG_TAG, "Install already running, skipping duplicate call");
            return;
        }
        clearOutputBuffer();
        try {
            executeInstallInternal(context, callback, postBootstrap);
        } finally {
            sInstallRunning.set(false);
        }
    }

    private static void executeInstallInternal(Context context,
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

        // Phase 0.7: prepare apt environment
        // dpkg may have half-configured packages from bootstrap; apt has no package lists.
        prepareAptEnvironment(callback);

        // Phase 1: local install script (primary method)
        setState(context, InstallState.INSTALLING);
        if (callback != null && callback.isCancelled()) return;
        try {
            if (callback != null) {
                callback.onStatus("Installing Hermes Agent...");
            }
            Logger.logInfo(LOG_TAG, "Starting local install script");
            runShellCommand(buildLocalInstallScript(), callback);
            setLastError(context, null);
            setState(context, InstallState.INSTALLED);
            return;
        } catch (Exception e) {
            errorLog.append("Local: ").append(e.getMessage()).append("\n");
            Logger.logWarn(LOG_TAG, "Local install failed: " + e.getMessage());
        }

        // Phase 2: mirror fallback (last resort)
        setState(context, InstallState.DOWNLOADING);
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

        String errorMsg = "All install methods failed\n" + errorLog;
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
     * Prepare the apt/dpkg environment before running the install script.
     * - dpkg --configure -a: fix half-configured packages from bootstrap
     * - apt update: fetch package lists (TUNA mirror already deployed)
     * Failures are logged but non-fatal — the install script may still succeed.
     */
    private static void prepareAptEnvironment(ProgressCallback callback) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        // Retry apt update, then upgrade all packages to prevent partial upgrade breakage.
        // When the install script later runs "pkg install X", apt won't trigger inconsistent
        // upgrades (e.g. libcurl upgraded but libngtcp2 not, causing linking failures).
        String diag = "echo '=== apt update (with retry) ==='; "
                + "_ok=false; for _i in $(seq 1 6); do "
                + "echo 'Attempt '$_i'...'; "
                + "if apt update 2>&1; then _ok=true; break; fi; "
                + "echo 'Retrying in 10s...'; sleep 10; "
                + "done; "
                + "if [ \"$_ok\" = false ]; then echo 'WARNING: apt update failed after 6 attempts'; fi; "
                + "echo '=== apt upgrade ==='; "
                + "_ug=false; for _i in $(seq 1 3); do "
                + "echo 'Upgrade attempt '$_i'...'; "
                + "if apt upgrade -y 2>&1; then _ug=true; break; fi; "
                + "echo 'Retrying in 10s...'; sleep 10; "
                + "done; "
                + "if [ \"$_ug\" = false ]; then echo 'WARNING: apt upgrade failed'; fi; "
                // TMPDIR symlinks: path_rewrite's shebang patching creates .rw_* temp files
                // that break $0-based path resolution. Pre-create symlinks so tools are found.
                + "echo '=== Preparing TMPDIR symlinks ==='; "
                + "for _t in clang clang++ cc c++ ld.lld; do "
                + "[ -x " + prefix + "/bin/$_t ] && ln -sf " + prefix + "/bin/$_t $TMPDIR/$_t; "
                + "done; "
                + "for _w in " + prefix + "/bin/*-android-*; do "
                + "[ -e \"$_w\" ] || continue; "
                + "ln -sf \"$_w\" \"$TMPDIR/$(basename \"$_w\")\"; "
                + "done";
        try {
            if (callback != null) callback.onOutput("Preparing apt environment...");
            runShellCommand(diag, callback);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "apt env preparation failed (non-fatal): " + e.getMessage());
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
     * Build the local install script that installs Hermes Agent without
     * relying on the upstream install.sh (which fails under path_rewrite
     * due to $0-based path resolution breaking in .rw_* temp files).
     */
    static String buildLocalInstallScript() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String mirror = MIRROR_PREFIXES.length > 0 ? MIRROR_PREFIXES[0] : "";
        return "set -e\n"
            + "\n"
            + "HERMES_DIR=\"$HOME/.hermes/hermes-agent\"\n"
            + "VENV_DIR=\"$HERMES_DIR/venv\"\n"
            + "HERMES_COMMIT=\"" + HERMES_AGENT_COMMIT + "\"\n"
            + "MIRROR_REPO_URL=\"" + mirror + GITHUB_REPO_URL + "\"\n"
            + "PREFIX=\"" + prefix + "\"\n"
            + "\n"
            + "echo '=== Step 1: Install system packages ==='\n"
            + "apt install -y python git nodejs clang rust make pkg-config "
            + "libffi openssl openssh ca-certificates curl ripgrep ffmpeg\n"
            + "\n"
            + "echo '=== Step 2: Clone hermes-agent source ==='\n"
            + "if [ -d \"$HERMES_DIR/.git\" ]; then\n"
            + "  cd \"$HERMES_DIR\"\n"
            + "  git fetch origin main || true\n"
            + "  git reset --hard \"$HERMES_COMMIT\" || true\n"
            + "else\n"
            + "  rm -rf \"$HERMES_DIR\"\n"
            + "  git clone \"$MIRROR_REPO_URL\" \"$HERMES_DIR\"\n"
            + "  cd \"$HERMES_DIR\"\n"
            + "  git checkout \"$HERMES_COMMIT\" || true\n"
            + "fi\n"
            + "\n"
            + "echo '=== Step 3: Create venv ==='\n"
            + "rm -rf \"$VENV_DIR\"\n"
            + "python -m venv \"$VENV_DIR\"\n"
            + "\n"
            + "echo '=== Step 4: Workaround path_rewrite $0 issue ==='\n"
            + "for _tool in clang clang++ cc c++ ld.lld; do\n"
            + "  [ -e \"$PREFIX/bin/$_tool\" ] && ln -sf \"$PREFIX/bin/$_tool\" \"$TMPDIR/$_tool\"\n"
            + "done\n"
            + "for _wrapper in \"$PREFIX/bin\"/*-android-*; do\n"
            + "  [ -e \"$_wrapper\" ] || continue\n"
            + "  ln -sf \"$_wrapper\" \"$TMPDIR/$(basename \"$_wrapper\")\"\n"
            + "done\n"
            + "\n"
            + "echo '=== Step 5: Build psutil from patched source ==='\n"
            + "_saved_ld_preload=\"$LD_PRELOAD\"\n"
            + "export LD_PRELOAD=\"\"\n"
            + "PSUTIL_VER=\"7.2.2\"\n"
            + "PSUTIL_TMP=\"$TMPDIR/psutil-build\"\n"
            + "rm -rf \"$PSUTIL_TMP\"\n"
            + "mkdir -p \"$PSUTIL_TMP\"\n"
            + "cd \"$PSUTIL_TMP\"\n"
            + "curl -fsSL --connect-timeout 30 --max-time 300 "
            + "\"https://files.pythonhosted.org/packages/source/p/psutil/psutil-${PSUTIL_VER}.tar.gz\" "
            + "| tar xz\n"
            + "cd \"psutil-${PSUTIL_VER}\"\n"
            + "sed -i 's/platform android is not supported/platform android - building with Termux toolchain/g' pyproject.toml\n"
            + "grep -q 'Termux toolchain' pyproject.toml || echo 'WARNING: psutil pyproject.toml patch may not have applied'\n"
            + "sed -i 's/LINUX = sys.platform.startswith(\"linux\")/LINUX = sys.platform.startswith((\"linux\", \"android\"))/g' psutil/_common.py\n"
            + "grep -q '\"android\"' psutil/_common.py || echo 'WARNING: psutil _common.py patch may not have applied'\n"
            + "env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install --no-build-isolation .\n"
            + "\n"
            + "echo '=== Step 5.5: Pre-install jiter (avoid Rust compilation) ==='\n"
            + "if ! env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install --only-binary :all: jiter 2>/dev/null; then\n"
            + "  echo 'No pre-built jiter wheel, attempting source build...'\n"
            + "  apt install -y maturin 2>/dev/null || true\n"
            + "  env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install --no-build-isolation jiter || \\\n"
            + "    echo 'WARNING: jiter build failed, will retry during main install'\n"
            + "fi\n"
            + "\n"
            + "echo '=== Step 6: Install hermes-agent ==='\n"
            + "cd \"$HERMES_DIR\"\n"
            + "_constraints=\"\"\n"
            + "[ -f \"$HERMES_DIR/constraints-termux.txt\" ] && _constraints=\"-c $HERMES_DIR/constraints-termux.txt\"\n"
            + "env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints -e '.[termux-all]' --no-deps || \\\n"
            + "  env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints -e '.[termux]' --no-deps || \\\n"
            + "  env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints -e . --no-deps\n"
            + "env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints --no-build-isolation -e '.[termux-all]' || \\\n"
            + "  env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints --no-build-isolation -e '.[termux]' || \\\n"
            + "  env -u LD_PRELOAD \"$VENV_DIR/bin/pip\" install $_constraints --no-build-isolation -e . || true\n"
            + "export LD_PRELOAD=\"$_saved_ld_preload\"\n"
            + "\n"
            + "echo '=== Step 7: Setup hermes command ==='\n"
            + "HERMES_BIN=\"$PREFIX/bin/hermes\"\n"
            + "VENV_HERMES=\"$VENV_DIR/bin/hermes\"\n"
            + "if [ -f \"$VENV_HERMES\" ]; then\n"
            + "  ln -sf \"$VENV_HERMES\" \"$HERMES_BIN\"\n"
            + "elif [ -f \"$HERMES_DIR/hermes_cli.py\" ]; then\n"
            + "  echo \"#!$VENV_DIR/bin/python\" > \"$HERMES_BIN\"\n"
            + "  echo \"import sys; sys.path.insert(0, '$HERMES_DIR'); from hermes_cli import main; main()\" >> \"$HERMES_BIN\"\n"
            + "  chmod 755 \"$HERMES_BIN\"\n"
            + "fi\n"
            + "\n"
            + "echo '=== Step 8: Copy config templates ==='\n"
            + "if [ -d \"$HERMES_DIR/config_templates\" ]; then\n"
            + "  mkdir -p \"$HOME/.hermes\"\n"
            + "  cp -n \"$HERMES_DIR/config_templates/\"* \"$HOME/.hermes/\" 2>/dev/null || true\n"
            + "fi\n"
            + "\n"
            + "echo '=== Local install complete ==='\n";
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

        // LD_PRELOAD rewrites /data/data/com.termux/ → /data/data/com.hermux/
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
                synchronized (sOutputBuffer) {
                    sOutputBuffer.append(line).append("\n");
                    if (sOutputBuffer.length() > MAX_BUFFER_SIZE) {
                        sOutputBuffer.delete(0, sOutputBuffer.length() - MAX_BUFFER_SIZE / 2);
                    }
                }
            }
        }

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Install script exited with code " + exit
                    + (output.length() > 0 ? "\n" + output : ""));
        }
    }
}
