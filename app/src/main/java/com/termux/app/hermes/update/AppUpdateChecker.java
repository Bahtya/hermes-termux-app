package com.termux.app.hermes.update;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.termux.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;

public class AppUpdateChecker {

    private static final String TAG = "AppUpdateChecker";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
    private static final int MAX_REDIRECTS = 5;

    private AppUpdateChecker() {}

    /**
     * Callbacks are invoked on a background thread.
     * Callers must use runOnUiThread() or Handler for UI operations.
     */
    public interface UpdateCheckCallback {
        void onUpdateAvailable(AppUpdateInfo info);
        void onNoUpdate();
        void onError(String message);
    }

    public static void checkForUpdate(Context context, UpdateCheckCallback callback) {
        new Thread(() -> {
            try {
                AppUpdateInfo info = doCheck(context);
                if (info == null) {
                    if (callback != null) callback.onNoUpdate();
                } else if (info.isNewerThan(BuildConfig.VERSION_CODE)) {
                    if (callback != null) callback.onUpdateAvailable(info);
                } else {
                    if (callback != null) callback.onNoUpdate();
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        }, "UpdateCheck").start();
    }

    static void silentCheckIfNeeded(Context context) {
        if (!AppUpdateConfig.shouldCheckUpdate(context)) return;

        new Thread(() -> {
            try {
                AppUpdateInfo info = doCheck(context);
                if (info != null
                        && info.isNewerThan(BuildConfig.VERSION_CODE)
                        && info.versionCode != AppUpdateConfig.getSkipVersionCode(context)) {
                    AppUpdateNotifier.showUpdateNotification(context, info);
                }
            } catch (Exception e) {
                Log.d(TAG, "Silent update check failed: " + e.getMessage());
            }
        }, "SilentUpdateCheck").start();
    }

    private static AppUpdateInfo doCheck(Context context) throws Exception {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";

        try {
            String json = querySupabase(context, abi);
            if (json != null) {
                AppUpdateInfo info = AppUpdateInfo.fromSupabaseJson(json);
                if (info != null) {
                    AppUpdateConfig.setLastCheckTime(context, System.currentTimeMillis());
                    return info;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Supabase check failed, trying fallback: " + e.getMessage());
        }

        String fallbackUrl = BuildConfig.UPDATE_FALLBACK_URL;
        String json = httpGet(fallbackUrl, CONNECT_TIMEOUT, READ_TIMEOUT, 0);
        if (json != null) {
            AppUpdateInfo info = AppUpdateInfo.fromFallbackJson(json);
            AppUpdateConfig.setLastCheckTime(context, System.currentTimeMillis());
            return info;
        }
        return null;
    }

    /**
     * Query Supabase PostgREST:
     * GET /rest/v1/app_versions?abi=eq.arm64-v8a&is_latest=eq.true&channel=eq.stable&order=version_code.desc&limit=1
     */
    private static String querySupabase(Context context, String abi) throws Exception {
        StringBuilder url = new StringBuilder(BuildConfig.SUPABASE_URL);
        url.append("/rest/v1/app_versions");
        url.append("?abi=eq.").append(URLEncoder.encode(abi, "UTF-8"));
        url.append("&is_latest=eq.true");
        url.append("&channel=eq.stable");
        url.append("&order=version_code.desc");
        url.append("&limit=1");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);

            String token = AppUpdateConfig.getAuthToken(context);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                return readStream(conn);
            } else {
                throw new Exception("Supabase HTTP " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String httpGet(String url, int connectTimeout, int readTimeout, int redirectCount) throws Exception {
        if (redirectCount > MAX_REDIRECTS) {
            throw new Exception("Too many redirects");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    conn.disconnect();
                    return httpGet(location, connectTimeout, readTimeout, redirectCount + 1);
                }
            }
            if (code == 200) {
                return readStream(conn);
            }
            throw new Exception("HTTP " + code + " for " + url);
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
}
