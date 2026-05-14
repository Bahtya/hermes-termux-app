package com.termux.app.hermes;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Extracts a pre-built Python venv from APK assets to the hermes-agent directory.
 * The venv is built in CI using Termux Docker and bundled as venv-aarch64.tar.gz.
 */
public class VenvExtractor {
    private static final String LOG_TAG = "VenvExtractor";
    private static final String ASSET_NAME = "venv-aarch64.tar.gz";

    public static boolean hasPrebuiltVenv(Context context) {
        try {
            context.getAssets().open(ASSET_NAME).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extract the pre-built venv from APK assets to ~/.hermes/hermes-agent/venv.
     * After extraction, fixes com.termux paths to com.hermux and sets execute permissions.
     *
     * @return true if extraction succeeded, false otherwise
     */
    public static boolean extractVenv(Context context) {
        String hermesDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/hermes-agent";
        String venvDir = hermesDir + "/venv";
        String tmpFile = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/venv-aarch64.tar.gz";

        // Check if venv already exists and is valid
        if (new File(venvDir + "/bin/python").exists()) {
            Logger.logInfo(LOG_TAG, "Venv already exists at " + venvDir + ", skipping extraction");
            return true;
        }

        // Copy tar.gz from assets to tmp
        Logger.logInfo(LOG_TAG, "Copying " + ASSET_NAME + " from APK assets to " + tmpFile);
        try {
            copyAsset(context, ASSET_NAME, tmpFile);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to copy venv asset: " + e.getMessage());
            return false;
        }

        // Extract using tar
        Logger.logInfo(LOG_TAG, "Extracting venv to " + hermesDir);
        new File(hermesDir).mkdirs();

        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(bashPath).exists()) {
            Logger.logError(LOG_TAG, "bash not available for venv extraction");
            return false;
        }

        String extractCmd =
            "cd \"" + hermesDir + "\" && "
            + "tar xzf \"" + tmpFile + "\" && "
            + "rm -f \"" + tmpFile + "\" && "
            // Fix com.termux → com.hermux paths in all text files
            + "find \"" + venvDir + "\" -type f -exec grep -l 'com\\.termux' {} + 2>/dev/null | "
            + "  while IFS= read -r f; do sed -i 's|/data/data/com\\.termux|/data/data/com.hermux|g' \"$f\"; done && "
            // Set execute permissions on bin/*
            + "chmod 755 \"" + venvDir + "/bin/\"* 2>/dev/null; "
            + "echo 'venv extraction complete'";

        try {
            ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", extractCmd);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":/system/bin:/system/xbin");
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    Logger.logInfo(LOG_TAG, "extract: " + line);
                }
            }

            int exit = p.waitFor();
            if (exit != 0) {
                Logger.logError(LOG_TAG, "Venv extraction failed (exit " + exit + "): " + output);
                return false;
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Venv extraction error: " + e.getMessage());
            return false;
        }

        // Verify
        if (!new File(venvDir + "/bin/python").exists()) {
            Logger.logError(LOG_TAG, "venv/bin/python not found after extraction");
            return false;
        }

        Logger.logInfo(LOG_TAG, "Venv extracted successfully to " + venvDir);
        return true;
    }

    private static void copyAsset(Context context, String assetName, String destPath) throws IOException {
        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }
}
