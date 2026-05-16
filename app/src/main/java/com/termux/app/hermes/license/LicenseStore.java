package com.termux.app.hermes.license;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 许可证本地存储（SharedPreferences + HMAC 完整性校验）
 * HMAC 密钥绑定 APK 签名证书，防止重打包和 SharedPreferences 篡改
 */
public class LicenseStore {

    private static final String TAG = "LicenseStore";
    private static final String PREFS_NAME = "hermes_license";

    private static final String KEY_LICENSE_ID = "license_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PLAN = "plan";
    private static final String KEY_ACTIVATED_AT = "activated_at";
    private static final String KEY_LAST_VERIFIED = "last_verified";
    private static final String KEY_VERIFICATION_TOKEN = "v_token";
    private static final String KEY_LICENSE_KEY = "l_key";
    private static final String KEY_HMAC = "hmac";

    private LicenseStore() {}

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void save(LicenseInfo info, String licenseKey, Context context) {
        SharedPreferences prefs = getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_LICENSE_ID, info.licenseId);
        editor.putString(KEY_DEVICE_ID, info.deviceId);
        editor.putString(KEY_PLAN, info.plan);
        editor.putLong(KEY_ACTIVATED_AT, info.activatedAt);
        editor.putLong(KEY_LAST_VERIFIED, info.lastVerifiedAt);
        editor.putString(KEY_VERIFICATION_TOKEN, info.verificationToken);
        editor.putString(KEY_LICENSE_KEY, licenseKey);

        String hmac = computeHmac(info, licenseKey, context);
        editor.putString(KEY_HMAC, hmac);
        editor.apply();
    }

    public static void updateVerification(String newToken, Context context) {
        SharedPreferences prefs = getPrefs(context);
        long now = System.currentTimeMillis();

        LicenseInfo old = load(context);
        if (old == null) return;

        LicenseInfo updated = new LicenseInfo(
                old.licenseId, old.deviceId, old.plan,
                old.activatedAt, now, newToken
        );
        String licenseKey = prefs.getString(KEY_LICENSE_KEY, "");
        String hmac = computeHmac(updated, licenseKey, context);

        prefs.edit()
                .putString(KEY_VERIFICATION_TOKEN, newToken)
                .putLong(KEY_LAST_VERIFIED, now)
                .putString(KEY_HMAC, hmac)
                .apply();
    }

    public static LicenseInfo load(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String licenseId = prefs.getString(KEY_LICENSE_ID, null);
        if (licenseId == null) return null;

        return new LicenseInfo(
                licenseId,
                prefs.getString(KEY_DEVICE_ID, null),
                prefs.getString(KEY_PLAN, null),
                prefs.getLong(KEY_ACTIVATED_AT, 0),
                prefs.getLong(KEY_LAST_VERIFIED, 0),
                prefs.getString(KEY_VERIFICATION_TOKEN, null)
        );
    }

    public static String loadLicenseKey(Context context) {
        return getPrefs(context).getString(KEY_LICENSE_KEY, null);
    }

    public static boolean validateIntegrity(Context context) {
        SharedPreferences prefs = getPrefs(context);
        LicenseInfo info = load(context);
        if (info == null) return true;

        String storedHmac = prefs.getString(KEY_HMAC, null);
        String licenseKey = prefs.getString(KEY_LICENSE_KEY, "");
        String computedHmac = computeHmac(info, licenseKey, context);

        if (storedHmac == null || !storedHmac.equals(computedHmac)) {
            Log.w(TAG, "License data integrity check failed");
            return false;
        }
        return true;
    }

    public static void clear(Context context) {
        getPrefs(context).edit().clear().apply();
    }

    /**
     * HMAC-SHA256 over all stored values.
     * Key is derived from APK signing certificate hash — 重打包的 APK 会产生不同的密钥，
     * 导致所有已存储的许可证数据 HMAC 校验失败。
     */
    private static String computeHmac(LicenseInfo info, String licenseKey, Context context) {
        String data = info.licenseId
                + "|" + info.deviceId
                + "|" + info.plan
                + "|" + info.activatedAt
                + "|" + info.lastVerifiedAt
                + "|" + info.verificationToken
                + "|" + licenseKey;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = deriveHmacKey(context);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "HMAC computation failed", e);
            return "";
        }
    }

    /**
     * HMAC 密钥从 APK 签名证书 + 固定种子派生。
     * - 不同签名证书 → 不同密钥 → 旧 HMAC 全部失效
     * - 固定种子确保同一 APK 产生的 HMAC 一致
     */
    private static byte[] deriveHmacKey(Context context) throws Exception {
        byte[] certHash = SignatureVerifier.getSigningCertHash(context);
        if (certHash == null) {
            // 无法获取签名（不应发生），使用 fallback
            certHash = new byte[32];
        }

        // 固定种子，与签名证书哈希混合
        byte[] seed = "HrmxL1c3ns3G4t3S3cur1tyK3y2026".getBytes("UTF-8");

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(seed);
        md.update(certHash);
        return md.digest();
    }
}
