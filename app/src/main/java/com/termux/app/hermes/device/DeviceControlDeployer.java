package com.termux.app.hermes.device;

import android.content.Context;
import android.util.Log;

import com.termux.app.hermes.HermesConfigManager;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Silently deploys the device control MCP server skill to ~/.hermes/tools/
 * and registers it in the Hermes Agent config.
 *
 * Called once after the setup wizard completes. Idempotent — safe to call
 * on every app start; will not overwrite existing user customizations.
 */
public class DeviceControlDeployer {

    private static final String TAG = "DeviceControlDeploy";
    private static final String TOOLS_DIR = HermesConfigManager.HERMES_CONFIG_DIR_PATH + "/tools";
    private static final String MCP_SERVER_SCRIPT = TOOLS_DIR + "/device_control.py";
    private static final String SHELL_SCRIPTS_DIR = TOOLS_DIR;
    private static final String MARKER_FILE = TOOLS_DIR + "/.deployed_version";
    private static final String CURRENT_VERSION = "1";

    private static final String MCP_SERVER_NAME = "device_control";

    private static final String[] SHELL_SCRIPTS = {
            "hermes-click", "hermes-type", "hermes-swipe",
            "hermes-key", "hermes-dump-ui", "hermes-adb", "hermes-status"
    };

    /**
     * Deploys the device control skill if not already deployed.
     * Safe to call from any thread; performs file I/O.
     */
    public static synchronized void deployIfNeeded(Context context) {
        if (isDeployed()) {
            return;
        }
        Log.i(TAG, "Deploying device control skill...");
        try {
            deployInternal(context);
            markDeployed();
            Log.i(TAG, "Device control skill deployed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to deploy device control skill", e);
        }
    }

    private static boolean isDeployed() {
        File marker = new File(MARKER_FILE);
        if (!marker.exists()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            String version = reader.readLine();
            return CURRENT_VERSION.equals(version != null ? version.trim() : "");
        } catch (IOException e) {
            return false;
        }
    }

    private static void markDeployed() {
        writeStringToFile(MARKER_FILE, CURRENT_VERSION);
    }

    private static void deployInternal(Context context) throws IOException {
        // 1. Create tools directory
        File toolsDir = new File(TOOLS_DIR);
        if (!toolsDir.exists() && !toolsDir.mkdirs()) {
            throw new IOException("Failed to create tools directory: " + TOOLS_DIR);
        }

        // 2. Deploy MCP server script from assets
        deployAsset(context, "device_tools/device_control.py", MCP_SERVER_SCRIPT);

        // 3. Deploy shell wrapper scripts from assets
        for (String script : SHELL_SCRIPTS) {
            deployAsset(context, "device_tools/" + script, SHELL_SCRIPTS_DIR + "/" + script);
            // Ensure executable
            File scriptFile = new File(SHELL_SCRIPTS_DIR + "/" + script);
            scriptFile.setExecutable(true, false);
        }

        // 4. Make MCP server executable
        new File(MCP_SERVER_SCRIPT).setExecutable(true, false);

        // 5. Register MCP server in config.yaml (idempotent)
        registerMcpServer();

        // 6. Symlink shell scripts to Termux bin for PATH access
        symlinkToBin();
    }

    private static void deployAsset(Context context, String assetPath, String targetPath) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath)) {
            writeStream(is, targetPath);
        } catch (IOException e) {
            throw new IOException("Asset not found: " + assetPath, e);
        }
    }

    private static void writeStream(InputStream is, String targetPath) throws IOException {
        File target = new File(targetPath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }

    private static void registerMcpServer() {
        String pythonPath = TermuxConstants.TERMUX_HOME_DIR_PATH
                + "/.hermes/hermes-agent/venv/bin/python3";
        String command = pythonPath + " " + MCP_SERVER_SCRIPT;

        HermesConfigManager config = HermesConfigManager.getInstance();
        // Only register if not already present
        String existing = config.getYamlValue("mcp_servers." + MCP_SERVER_NAME + ".command");
        if (existing == null || existing.isEmpty()) {
            config.addMcpServer(MCP_SERVER_NAME, command);
            Log.i(TAG, "Registered MCP server: " + MCP_SERVER_NAME);
        }
    }

    private static void symlinkToBin() {
        String binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        for (String script : SHELL_SCRIPTS) {
            String target = SHELL_SCRIPTS_DIR + "/" + script;
            String link = binDir + "/" + script;
            File linkFile = new File(link);
            // Don't overwrite existing user scripts
            if (linkFile.exists()) continue;
            try {
                java.nio.file.Files.createSymbolicLink(
                        java.nio.file.Paths.get(link),
                        java.nio.file.Paths.get(target));
            } catch (Exception e) {
                // Fallback: create a wrapper script
                try {
                    writeStringToFile(link,
                            "#!/data/data/com.hermux/files/usr/bin/bash\n"
                                    + "exec " + target + " \"$@\"\n");
                    new File(link).setExecutable(true, false);
                } catch (Exception e2) {
                    Log.w(TAG, "Failed to link " + script + ": " + e2.getMessage());
                }
            }
        }
    }

    private static void writeStringToFile(String path, String content) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(file))) {
            os.write(content.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + path + ": " + e.getMessage());
        }
    }
}
