package com.termux.app;

import android.content.Context;
import android.system.Os;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Installs Hermes Agent on first launch and deploys a boot service script
 * so that the Hermes gateway starts automatically on device boot.
 * <p>
 * All operations are idempotent — safe to call multiple times.
 */
public class HermesInstaller {

    private static final String LOG_TAG = "HermesInstaller";

    private static final String HERMES_MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final String HERMES_BOOT_SCRIPT =
            TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH + "/hermes-gateway";
    private static final String HERMES_INSTALL_URL =
            "https://hermes-agent.nousresearch.com/install.sh";

    private HermesInstaller() {}

    /** Run Hermes setup if it hasn't been done yet. */
    static void installIfNeeded(Context context) {
        if (new File(HERMES_MARKER_FILE).exists()) {
            Logger.logInfo(LOG_TAG, "Hermes already installed, skipping.");
            return;
        }

        new Thread(() -> {
            try {
                deployBootScript(context);
                runInstallScript();
                markInstalled();
                Logger.logInfo(LOG_TAG, "Hermes installation complete.");
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Hermes installation failed:\n" + e.getMessage());
            }
        }).start();
    }

    private static void deployBootScript(Context context) throws Exception {
        File bootDir = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR;
        FileUtils.createDirectoryFile(bootDir.getAbsolutePath());

        String script = "#!/data/data/com.termux/files/usr/bin/sh\n"
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

    private static void runInstallScript() throws Exception {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";

        if (!new File(bashPath).exists() || !new File(curlPath).exists()) {
            Logger.logInfo(LOG_TAG, "bash/curl not yet available, skipping install script.");
            return;
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
        byte[] buf = new byte[8192];
        while (p.getInputStream().read(buf) != -1) { /* drain */ }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Hermes install script exited with code " + exit);
        }
    }

    private static void markInstalled() throws Exception {
        try (FileOutputStream out = new FileOutputStream(HERMES_MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
    }
}
