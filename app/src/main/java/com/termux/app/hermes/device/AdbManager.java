package com.termux.app.hermes.device;

import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Manages ADB over TCP self-connection and command execution.
 * Uses the Termux-packaged `adb` binary from android-tools.
 */
public class AdbManager {

    private static final String TAG = "AdbManager";
    private static volatile boolean sConnected = false;

    private static String adbPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/adb";
    }

    private static String bashPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
    }

    public static boolean isAdbInstalled() {
        return new java.io.File(adbPath()).exists();
    }

    public static synchronized JSONObject connect(String host, int port) {
        if (!isAdbInstalled()) {
            return HermuxAccessibilityService.errorResult("adb not installed. Run: pkg install android-tools");
        }
        try {
            String target = host + ":" + port;
            String output = execRaw("adb connect " + target);
            sConnected = output != null && output.contains("connected");
            if (sConnected) {
                return HermuxAccessibilityService.okResult("connected to " + target);
            }
            return HermuxAccessibilityService.errorResult("connect failed: " + output);
        } catch (Exception e) {
            return HermuxAccessibilityService.errorResult("connect error: " + e.getMessage());
        }
    }

    public static synchronized JSONObject disconnect() {
        try {
            execRaw("adb disconnect");
            sConnected = false;
            return HermuxAccessibilityService.okResult("disconnected");
        } catch (Exception e) {
            return HermuxAccessibilityService.errorResult("disconnect error: " + e.getMessage());
        }
    }

    public static JSONObject execShell(String command) {
        if (!isAdbInstalled()) {
            return HermuxAccessibilityService.errorResult("adb not installed");
        }
        if (!sConnected) {
            return HermuxAccessibilityService.errorResult("adb not connected. POST /adb/connect first");
        }
        try {
            String output = execRaw("adb shell " + command);
            JSONObject result = HermuxAccessibilityService.okResult("ok");
            result.put("output", output != null ? output : "");
            return result;
        } catch (Exception e) {
            return HermuxAccessibilityService.errorResult("exec error: " + e.getMessage());
        }
    }

    public static JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("adbInstalled", isAdbInstalled());
            status.put("connected", sConnected);
            if (isAdbInstalled()) {
                String devices = execRaw("adb devices");
                status.put("devices", devices != null ? devices : "");
            }
        } catch (Exception e) {
            try { status.put("error", e.getMessage()); } catch (JSONException ignored) {}
        }
        return status;
    }

    /**
     * Run a command via Termux bash and return stdout+stderr combined.
     * The command is prefixed with the adb path if it starts with "adb".
     */
    private static String execRaw(String command) throws Exception {
        if (command.startsWith("adb ")) {
            command = adbPath() + " " + command.substring(4);
        }

        ProcessBuilder pb = new ProcessBuilder(
                bashPath(), "-c", command
        );
        pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");
        pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);

        String pathRewriteLib = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH + "/libpath_rewrite.so";
        if (new java.io.File(pathRewriteLib).exists()) {
            pb.environment().put("LD_PRELOAD", pathRewriteLib);
        }

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("command timed out");
        }

        return output.toString().trim();
    }

    /**
     * Ensure android-tools is installed. Call from installer or setup.
     */
    public static boolean ensureInstalled() {
        if (isAdbInstalled()) return true;
        try {
            String output = execRaw("pkg install -y android-tools 2>&1");
            Log.i(TAG, "android-tools install: " + output);
            return isAdbInstalled();
        } catch (Exception e) {
            Log.e(TAG, "Failed to install android-tools", e);
            return false;
        }
    }
}
