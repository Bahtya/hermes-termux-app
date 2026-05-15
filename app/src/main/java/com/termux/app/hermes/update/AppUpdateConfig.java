package com.termux.app.hermes.update;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class AppUpdateConfig {

    private static final String PREFS_NAME = "hermes_app_update";

    private static final String PREF_LAST_CHECK_TIME = "last_check_time";
    private static final String PREF_SKIP_VERSION_CODE = "skip_version_code";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String PREF_AUTH_TOKEN = "auth_token";

    static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private AppUpdateConfig() {}

    static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean shouldCheckUpdate(Context context) {
        long last = getPrefs(context).getLong(PREF_LAST_CHECK_TIME, 0);
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS;
    }

    static void setLastCheckTime(Context context, long time) {
        getPrefs(context).edit().putLong(PREF_LAST_CHECK_TIME, time).apply();
    }

    static int getSkipVersionCode(Context context) {
        return getPrefs(context).getInt(PREF_SKIP_VERSION_CODE, 0);
    }

    static void setSkipVersionCode(Context context, int versionCode) {
        getPrefs(context).edit().putInt(PREF_SKIP_VERSION_CODE, versionCode).apply();
    }

    static String getDeviceId(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String id = prefs.getString(PREF_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_DEVICE_ID, id).apply();
        }
        return id;
    }

    static String getAuthToken(Context context) {
        return getPrefs(context).getString(PREF_AUTH_TOKEN, "");
    }

    static void setAuthToken(Context context, String token) {
        getPrefs(context).edit().putString(PREF_AUTH_TOKEN, token).apply();
    }
}
