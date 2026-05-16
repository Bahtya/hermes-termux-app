package com.termux.app.hermes.license;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;

/**
 * 设备指纹生成工具
 * 使用多个硬件标识符的组合 SHA-256 哈希
 */
public class DeviceFingerprint {

    private DeviceFingerprint() {}

    /**
     * 计算设备指纹: SHA-256(androidId | brand | model | manufacturer | packageName)
     */
    public static String compute(Context context) {
        String androidId = getAndroidId(context);
        String raw = androidId
                + "|" + Build.BRAND
                + "|" + Build.MODEL
                + "|" + Build.MANUFACTURER
                + "|" + context.getPackageName();
        return sha256Hex(raw);
    }

    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + model;
    }

    public static String getAndroidId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.toLowerCase());
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
