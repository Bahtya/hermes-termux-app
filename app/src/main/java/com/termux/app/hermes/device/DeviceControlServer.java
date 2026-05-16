package com.termux.app.hermes.device;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server on localhost:18720 that exposes device control
 * capabilities (AccessibilityService + ADB) to the Hermes Agent process.
 * Uses java.net.ServerSocket (no com.sun dependency) for Android compatibility.
 */
public class DeviceControlServer {

    private static final String TAG = "DeviceCtrlServer";
    static final int PORT = 18720;

    private static ServerSocket sServerSocket;
    private static ExecutorService sExecutor;

    public static synchronized void start(HermuxAccessibilityService service) {
        if (sServerSocket != null) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", PORT));
            sServerSocket = ss;
            sExecutor = Executors.newSingleThreadExecutor();
            sExecutor.submit(() -> acceptLoop(ss, service));
            Log.i(TAG, "Device control server started on port " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
        }
    }

    public static synchronized void stop() {
        if (sServerSocket != null) {
            try { sServerSocket.close(); } catch (IOException ignored) {}
            sServerSocket = null;
        }
        if (sExecutor != null) {
            sExecutor.shutdownNow();
            sExecutor = null;
        }
        Log.i(TAG, "Device control server stopped");
    }

    private static void acceptLoop(ServerSocket ss, HermuxAccessibilityService service) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket client = ss.accept();
                sExecutor.submit(() -> handle(client, service));
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Accept error", e);
                }
            }
        }
    }

    private static void handle(Socket client, HermuxAccessibilityService service) {
        try (Socket s = client) {
            s.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));

            // Read request line: "METHOD /path HTTP/1.1"
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1].split("\\?")[0];

            // Read headers
            int contentLength = 0;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String key = headerLine.substring(0, colon).trim().toLowerCase();
                    if ("content-length".equals(key)) {
                        contentLength = Integer.parseInt(headerLine.substring(colon + 1).trim());
                    }
                }
            }

            // Read body
            JSONObject body = new JSONObject();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = reader.read(buf, read, contentLength - read);
                    if (r < 0) break;
                    read += r;
                }
                if (read > 0) {
                    body = new JSONObject(new String(buf, 0, read));
                }
            }

            JSONObject result = route(path, method, body, service);
            sendJson(s.getOutputStream(), result);
        } catch (Exception e) {
            Log.e(TAG, "Error handling request", e);
        }
    }

    private static JSONObject route(String path, String method, JSONObject body,
                                     HermuxAccessibilityService service) {
        try {
            // ADB endpoints (don't need main thread)
            if ("/adb/shell".equals(path)) {
                if (!"POST".equals(method)) return HermuxAccessibilityService.errorResult("POST only");
                return AdbManager.execShell(body.optString("command", ""));
            }
            if ("/adb/connect".equals(path)) {
                if (!"POST".equals(method)) return HermuxAccessibilityService.errorResult("POST only");
                return AdbManager.connect(body.optString("host", "localhost"), body.optInt("port", 5555));
            }
            if ("/adb/status".equals(path)) {
                return AdbManager.getStatus();
            }
            if ("/status".equals(path)) {
                JSONObject status = new JSONObject();
                status.put("ok", true);
                status.put("a11y", HermuxAccessibilityService.isRunning());
                status.put("adb", AdbManager.getStatus());
                return status;
            }

            // A11y endpoints — must run on main thread
            if (service == null) {
                return HermuxAccessibilityService.errorResult("accessibility service not running");
            }
            return service.callOnMainThread(() -> routeA11y(path, body, service));
        } catch (Exception e) {
            return HermuxAccessibilityService.errorResult(e.getMessage());
        }
    }

    private static JSONObject routeA11y(String path, JSONObject body,
                                          HermuxAccessibilityService svc) throws Exception {
        switch (path) {
            case "/click":
                if (body.has("text")) return svc.clickByText(body.getString("text"));
                if (body.has("id")) return svc.clickById(body.getString("id"));
                if (body.has("x") && body.has("y"))
                    return svc.clickAt(body.getInt("x"), body.getInt("y"));
                return HermuxAccessibilityService.errorResult("missing text, id, or x/y");
            case "/type":
                if (!body.has("text")) return HermuxAccessibilityService.errorResult("missing text");
                return svc.typeText(body.getString("text"));
            case "/swipe":
                return svc.swipe(
                        body.optInt("x1", 0), body.optInt("y1", 0),
                        body.optInt("x2", 0), body.optInt("y2", 0),
                        body.optInt("duration", 300));
            case "/key": {
                int kc = globalKeyCode(body.optString("key", ""));
                if (kc < 0) return HermuxAccessibilityService.errorResult("unknown key: " + body.optString("key"));
                return svc.pressKey(kc);
            }
            case "/scroll":
                return "backward".equals(body.optString("direction", "forward"))
                        ? svc.scrollBackward() : svc.scrollForward();
            case "/ui":
                return svc.dumpUI();
            case "/current_app":
                return svc.getCurrentApp();
            default:
                return HermuxAccessibilityService.errorResult("unknown path: " + path);
        }
    }

    static void sendJson(OutputStream os, JSONObject data) throws IOException {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }

    static int globalKeyCode(String key) {
        switch (key.toLowerCase()) {
            case "back": return 1;
            case "home": return 2;
            case "recents":
            case "recent": return 3;
            case "notifications": return 4;
            case "quick_settings": return 5;
            case "power_dialog": return 6;
            case "lock_screen":
                if (android.os.Build.VERSION.SDK_INT >= 28) return 8;
                return -1;
            case "take_screenshot":
                if (android.os.Build.VERSION.SDK_INT >= 28) return 9;
                return -1;
            default: return -1;
        }
    }
}
