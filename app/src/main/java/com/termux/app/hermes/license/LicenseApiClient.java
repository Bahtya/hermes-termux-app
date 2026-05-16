package com.termux.app.hermes.license;

import android.util.Log;

import com.termux.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Supabase Edge Functions HTTP 客户端
 * 复用 AppUpdateChecker.java 的 HttpURLConnection 模式
 */
public class LicenseApiClient {

    private static final String TAG = "LicenseApiClient";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    private LicenseApiClient() {}

    /**
     * 激活许可证
     * POST /functions/v1/activate
     */
    public static JSONObject activate(String licenseKey, String fingerprint,
                                       String deviceName, String androidId, String appVersion) throws Exception {
        JSONObject body = new JSONObject();
        body.put("license_key", licenseKey);
        body.put("device_fingerprint", fingerprint);
        body.put("device_name", deviceName);
        body.put("android_id", androidId);
        body.put("app_version", appVersion);
        return post("/functions/v1/activate", body);
    }

    /**
     * 验证许可证（心跳）
     * POST /functions/v1/verify
     */
    public static JSONObject verify(String licenseKey, String fingerprint,
                                     String verificationToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("license_key", licenseKey);
        body.put("device_fingerprint", fingerprint);
        body.put("verification_token", verificationToken);
        return post("/functions/v1/verify", body);
    }

    /**
     * 解绑设备
     * POST /functions/v1/deactivate
     */
    public static JSONObject deactivate(String licenseKey, String fingerprint,
                                         String verificationToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("license_key", licenseKey);
        body.put("device_fingerprint", fingerprint);
        body.put("verification_token", verificationToken);
        return post("/functions/v1/deactivate", body);
    }

    private static JSONObject post(String path, JSONObject body) throws Exception {
        String url = BuildConfig.SUPABASE_URL + path;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            conn.setDoOutput(true);

            byte[] bodyBytes = body.toString().getBytes("UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String responseStr;
            if (code >= 200 && code < 300) {
                responseStr = readStream(conn);
            } else {
                responseStr = readErrorStream(conn);
                Log.w(TAG, "Edge Function " + path + " returned HTTP " + code + ": " + responseStr);
            }

            return new JSONObject(responseStr);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private static String readErrorStream(HttpURLConnection conn) throws Exception {
        java.io.InputStream errorStream = conn.getErrorStream();
        if (errorStream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(errorStream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
