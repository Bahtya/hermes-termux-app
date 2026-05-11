package com.termux.app.hermes;

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

    static final String INSTALL_URL_DIRECT =
            "https://hermes-agent.nousresearch.com/install.sh";

    static final String INSTALL_URL_GITHUB_RAW =
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh";

    static final String GITHUB_REPO_URL =
            "https://github.com/NousResearch/hermes-agent.git";

    static final String[] MIRROR_PREFIXES = {
            "https://ghfast.top/",
    };

    private HermesInstallHelper() {}

    public interface ProgressCallback {
        void onStatus(String message);
        boolean isCancelled();
    }

    /**
     * Run the install script with automatic mirror fallback.
     * Phase 1: direct connection (up to maxDirectRetries attempts, 5s delay).
     * Phase 2: each mirror in MIRROR_PREFIXES (1 attempt per mirror).
     */
    public static void executeInstall(int maxDirectRetries, ProgressCallback callback) throws Exception {
        StringBuilder errorLog = new StringBuilder();

        // Phase 1: direct attempts
        for (int attempt = 1; attempt <= maxDirectRetries; attempt++) {
            if (callback != null && callback.isCancelled()) return;
            try {
                if (callback != null) {
                    callback.onStatus("Downloading Hermes Agent... (attempt " + attempt + "/" + maxDirectRetries + ")");
                }
                runShellCommand(buildInstallCommand(false, null));
                return; // success
            } catch (Exception e) {
                errorLog.append("Direct attempt ").append(attempt).append(": ")
                        .append(e.getMessage()).append("\n");
                Logger.logWarn(LOG_TAG, "Direct install attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < maxDirectRetries) {
                    if (callback != null) {
                        callback.onStatus("Retrying in 5s... (" + attempt + "/" + maxDirectRetries + ")");
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
                    callback.onStatus("Direct connection failed, trying " + mirror + " mirror...");
                }
                Logger.logInfo(LOG_TAG, "Falling back to mirror: " + mirror);
                runShellCommand(buildInstallCommand(true, mirror));
                return; // success
            } catch (Exception e) {
                errorLog.append("Mirror (").append(mirror).append("): ")
                        .append(e.getMessage()).append("\n");
                Logger.logWarn(LOG_TAG, "Mirror " + mirror + " failed: " + e.getMessage());
            }
        }

        throw new RuntimeException("All download methods failed\n" + errorLog);
    }

    /**
     * Build the shell command for downloading and executing the install script.
     *
     * Direct mode:  curl -fsSL DIRECT_URL | bash
     * Mirror mode:  curl -fsSL MIRROR+RAW_URL | sed 's|GITHUB_URL|MIRROR+GITHUB_URL|g' | bash
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
