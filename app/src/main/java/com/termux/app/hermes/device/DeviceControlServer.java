package com.termux.app.hermes.device;

import android.util.Log;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server on localhost:18720 that exposes device control
 * capabilities (AccessibilityService + ADB) to the Hermes Agent process.
 */
public class DeviceControlServer {

    private static final String TAG = "DeviceCtrlServer";
    static final int PORT = 18720;

    private static HttpServer sServer;

    public static synchronized void start(HermuxAccessibilityService service) {
        if (sServer != null) return;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.setExecutor(Executors.newSingleThreadExecutor());

            server.createContext("/click", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    if (body.has("text")) return svc.clickByText(body.getString("text"));
                    if (body.has("id")) return svc.clickById(body.getString("id"));
                    if (body.has("x") && body.has("y"))
                        return svc.clickAt(body.getInt("x"), body.getInt("y"));
                    return HermuxAccessibilityService.errorResult("missing text, id, or x/y");
                }
            });

            server.createContext("/type", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    if (!body.has("text")) return HermuxAccessibilityService.errorResult("missing text");
                    return svc.typeText(body.getString("text"));
                }
            });

            server.createContext("/swipe", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    int x1 = body.optInt("x1", 0);
                    int y1 = body.optInt("y1", 0);
                    int x2 = body.optInt("x2", 0);
                    int y2 = body.optInt("y2", 0);
                    int duration = body.optInt("duration", 300);
                    return svc.swipe(x1, y1, x2, y2, duration);
                }
            });

            server.createContext("/key", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    int kc = globalKeyCode(body.optString("key", ""));
                    if (kc < 0) return HermuxAccessibilityService.errorResult("unknown key: " + body.optString("key"));
                    return svc.pressKey(kc);
                }
            });

            server.createContext("/scroll", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    String dir = body.optString("direction", "forward");
                    return "backward".equals(dir) ? svc.scrollBackward() : svc.scrollForward();
                }
            });

            server.createContext("/ui", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    return svc.dumpUI();
                }
            });

            server.createContext("/current_app", new ServiceHandler(service) {
                @Override
                protected JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception {
                    return svc.getCurrentApp();
                }
            });

            // ADB endpoints
            server.createContext("/adb/shell", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    JSONObject body = readBody(exchange);
                    String cmd = body.optString("command", "");
                    sendJson(exchange, 200, AdbManager.execShell(cmd));
                } else {
                    sendJson(exchange, 405, HermuxAccessibilityService.errorResult("POST only"));
                }
            });

            server.createContext("/adb/connect", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    JSONObject body = readBody(exchange);
                    String host = body.optString("host", "localhost");
                    int port = body.optInt("port", 5555);
                    sendJson(exchange, 200, AdbManager.connect(host, port));
                } else {
                    sendJson(exchange, 405, HermuxAccessibilityService.errorResult("POST only"));
                }
            });

            server.createContext("/adb/status", exchange -> {
                sendJson(exchange, 200, AdbManager.getStatus());
            });

            server.createContext("/status", exchange -> {
                JSONObject status = new JSONObject();
                status.put("ok", true);
                status.put("a11y", HermuxAccessibilityService.isRunning());
                status.put("adb", AdbManager.getStatus());
                sendJson(exchange, 200, status);
            });

            server.start();
            sServer = server;
            Log.i(TAG, "Device control server started on port " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server", e);
        }
    }

    public static synchronized void stop() {
        if (sServer != null) {
            sServer.stop(0);
            sServer = null;
            Log.i(TAG, "Device control server stopped");
        }
    }

    // --- Helpers ---

    static JSONObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) return new JSONObject();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("Invalid JSON body", e);
        }
    }

    static void sendJson(HttpExchange exchange, int code, JSONObject data) throws IOException {
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static int globalKeyCode(String key) {
        switch (key.toLowerCase()) {
            case "back": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
            case "home": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
            case "recents":
            case "recent": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
            case "notifications": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
            case "quick_settings": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
            case "power_dialog": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOGS;
            case "lock_screen":
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                return -1;
            case "take_screenshot":
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
                return -1;
            default: return -1;
        }
    }

    abstract static class ServiceHandler implements HttpHandler {
        private final HermuxAccessibilityService mService;

        ServiceHandler(HermuxAccessibilityService service) {
            mService = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (mService == null) {
                sendJson(exchange, 503, HermuxAccessibilityService.errorResult("accessibility service not running"));
                return;
            }
            try {
                JSONObject body = "POST".equals(exchange.getRequestMethod()) ? readBody(exchange) : new JSONObject();
                JSONObject result = handleA11y(mService, body);
                sendJson(exchange, 200, result);
            } catch (Exception e) {
                sendJson(exchange, 500, HermuxAccessibilityService.errorResult(e.getMessage()));
            }
        }

        protected abstract JSONObject handleA11y(HermuxAccessibilityService svc, JSONObject body) throws Exception;
    }
}
