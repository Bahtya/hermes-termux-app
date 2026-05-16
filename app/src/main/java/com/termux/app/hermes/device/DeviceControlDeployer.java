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
        HermesConfigManager.writeStringToFile(MARKER_FILE, CURRENT_VERSION);
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
        // Try assets first
        try (InputStream is = context.getAssets().open(assetPath)) {
            writeStream(is, targetPath);
            return;
        } catch (IOException ignored) {
            // Asset not found, try inline generation
        }

        // Fallback: generate inline for the MCP server
        if (assetPath.endsWith("device_control.py")) {
            writeStringToFile(targetPath, generateMcpServerScript());
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

    /**
     * Generate the MCP server script inline as a fallback.
     * This ensures deployment works even without assets.
     */
    private static String generateMcpServerScript() {
        return "#!/usr/bin/env python3\n"
                + "# Hermux Device Control MCP Server\n"
                + "# Auto-deployed by Hermux app\n"
                + "import json, sys, urllib.request, urllib.error\n"
                + "SERVER = \"http://localhost:18720\"\n"
                + "def send_result(r):\n"
                + "    sys.stdout.write(json.dumps(r) + \"\\n\"); sys.stdout.flush()\n"
                + "def make_resp(rid, r): return {\"jsonrpc\":\"2.0\",\"id\":rid,\"result\":r}\n"
                + "def make_err(rid, c, m): return {\"jsonrpc\":\"2.0\",\"id\":rid,\"error\":{\"code\":c,\"message\":m}}\n"
                + "def http_get(p):\n"
                + "    try:\n"
                + "        with urllib.request.urlopen(SERVER+p, timeout=10) as r: return json.loads(r.read().decode())\n"
                + "    except Exception as e: return {\"ok\":False,\"error\":str(e)}\n"
                + "def http_post(p, b):\n"
                + "    try:\n"
                + "        d=json.dumps(b).encode()\n"
                + "        req=urllib.request.Request(SERVER+p,data=d,headers={\"Content-Type\":\"application/json\"})\n"
                + "        with urllib.request.urlopen(req, timeout=10) as r: return json.loads(r.read().decode())\n"
                + "    except Exception as e: return {\"ok\":False,\"error\":str(e)}\n"
                + "TOOLS=[\n"
                + "  {\"name\":\"click\",\"description\":\"Click a UI element. Use text, id, or x/y.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}}}},\n"
                + "  {\"name\":\"type_text\",\"description\":\"Type text into focused input field.\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}},\n"
                + "  {\"name\":\"swipe\",\"description\":\"Swipe gesture.\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"x1\",\"y1\",\"x2\",\"y2\"],\"properties\":{\"x1\":{\"type\":\"integer\"},\"y1\":{\"type\":\"integer\"},\"x2\":{\"type\":\"integer\"},\"y2\":{\"type\":\"integer\"},\"duration\":{\"type\":\"integer\"}}}},\n"
                + "  {\"name\":\"press_key\",\"description\":\"Press global key: back,home,recents,notifications,quick_settings,power_dialog,lock_screen,take_screenshot.\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"}}}},\n"
                + "  {\"name\":\"scroll\",\"description\":\"Scroll forward or backward.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\",\"enum\":[\"forward\",\"backward\"]}}}},\n"
                + "  {\"name\":\"dump_ui\",\"description\":\"Dump current UI hierarchy as JSON.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},\n"
                + "  {\"name\":\"get_current_app\",\"description\":\"Get current foreground app info.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},\n"
                + "  {\"name\":\"adb_shell\",\"description\":\"Execute ADB shell command.\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"}}}},\n"
                + "  {\"name\":\"adb_connect\",\"description\":\"Connect ADB over TCP.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"}}}},\n"
                + "  {\"name\":\"device_status\",\"description\":\"Check device control status.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},\n"
                + "]\n"
                + "def call_tool(n, a):\n"
                + "    m={\"click\":lambda:http_post(\"/click\",a),\"type_text\":lambda:http_post(\"/type\",a),\"swipe\":lambda:http_post(\"/swipe\",a),\"press_key\":lambda:http_post(\"/key\",a),\"scroll\":lambda:http_post(\"/scroll\",a),\"dump_ui\":lambda:http_get(\"/ui\"),\"get_current_app\":lambda:http_get(\"/current_app\"),\"adb_shell\":lambda:http_post(\"/adb/shell\",{\"command\":a.get(\"command\",\"\")}),\"adb_connect\":lambda:http_post(\"/adb/connect\",{\"host\":a.get(\"host\",\"localhost\"),\"port\":a.get(\"port\",5555)}),\"device_status\":lambda:http_get(\"/status\")}\n"
                + "    f=m.get(n)\n"
                + "    return f() if f else {\"ok\":False,\"error\":\"Unknown tool: \"+n}\n"
                + "def handle(req):\n"
                + "    rid=req.get(\"id\"); method=req.get(\"method\"); params=req.get(\"params\",{})\n"
                + "    if method==\"initialize\": return make_resp(rid,{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"hermux-device-control\",\"version\":\"1.0.0\"}})\n"
                + "    if method==\"notifications/initialized\": return None\n"
                + "    if method==\"tools/list\": return make_resp(rid,{\"tools\":TOOLS})\n"
                + "    if method==\"tools/call\":\n"
                + "        r=call_tool(params.get(\"name\",\"\"),params.get(\"arguments\",{}))\n"
                + "        txt=json.dumps(r,ensure_ascii=False) if \"ui\" in r or \"app\" in r else r.get(\"output\",r.get(\"message\",\"ok\" if r.get(\"ok\") else r.get(\"error\",\"error\")))\n"
                + "        return make_resp(rid,{\"content\":[{\"type\":\"text\",\"text\":str(txt)}]})\n"
                + "    if method==\"shutdown\": return make_resp(rid,{})\n"
                + "    return make_err(rid,-32601,\"Method not found\")\n"
                + "for line in sys.stdin:\n"
                + "    line=line.strip()\n"
                + "    if not line: continue\n"
                + "    try: req=json.loads(line)\n"
                + "    except: continue\n"
                + "    resp=handle(req)\n"
                + "    if resp is not None: send_result(resp)\n"
                + "    if req.get(\"method\")==\"shutdown\": break\n";
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
                    HermesConfigManager.writeStringToFile(link,
                            "#!/data/data/com.hermux/files/usr/bin/bash\n"
                                    + "exec " + target + " \"$@\"\n");
                    new File(link).setExecutable(true, false);
                } catch (Exception e2) {
                    Log.w(TAG, "Failed to link " + script + ": " + e2.getMessage());
                }
            }
        }
    }

    /** Write a string to a file (package-private utility for marker). */
    static void writeStringToFile(String path, String content) {
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
