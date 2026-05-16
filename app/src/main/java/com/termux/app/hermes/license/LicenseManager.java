package com.termux.app.hermes.license;

import android.content.Context;
import android.util.Log;

import com.termux.BuildConfig;

import org.json.JSONObject;

/**
 * 许可证管理器（单例）
 * 负责激活、验证、离线逻辑
 */
public class LicenseManager {

    private static final String TAG = "LicenseManager";

    // 离线宽限期阈值（毫秒）
    private static final long VERIFY_INTERVAL = 24 * 60 * 60 * 1000L;       // 24小时
    private static final long GRACE_PERIOD_THRESHOLD = 7 * 24 * 60 * 60 * 1000L;  // 7天
    private static final long LOCKOUT_THRESHOLD = 14 * 24 * 60 * 60 * 1000L;      // 14天

    private static volatile LicenseManager instance;

    private LicenseManager() {}

    public static LicenseManager getInstance() {
        if (instance == null) {
            synchronized (LicenseManager.class) {
                if (instance == null) {
                    instance = new LicenseManager();
                }
            }
        }
        return instance;
    }

    public interface LicenseCallback {
        void onSuccess(LicenseInfo info);
        void onError(String code, String message);
    }

    // ---- 状态查询 ----

    public boolean isLicensed(Context context) {
        if (!LicenseStore.validateIntegrity(context)) return false;
        LicenseInfo info = LicenseStore.load(context);
        return info != null && info.isActivated();
    }

    public boolean isGracePeriod(Context context) {
        if (!isLicensed(context)) return false;
        LicenseInfo info = LicenseStore.load(context);
        long elapsed = System.currentTimeMillis() - info.lastVerifiedAt;
        return elapsed > GRACE_PERIOD_THRESHOLD && elapsed <= LOCKOUT_THRESHOLD;
    }

    public boolean isExpired(Context context) {
        if (!isLicensed(context)) return false;
        LicenseInfo info = LicenseStore.load(context);
        return (System.currentTimeMillis() - info.lastVerifiedAt) > LOCKOUT_THRESHOLD;
    }

    public int getDaysOffline(Context context) {
        LicenseInfo info = LicenseStore.load(context);
        if (info == null) return Integer.MAX_VALUE;
        return (int) ((System.currentTimeMillis() - info.lastVerifiedAt) / (24 * 60 * 60 * 1000L));
    }

    public String getPlan(Context context) {
        LicenseInfo info = LicenseStore.load(context);
        return info != null ? info.plan : null;
    }

    // ---- 激活 ----

    public void activate(Context context, String licenseKey, LicenseCallback callback) {
        new Thread(() -> {
            try {
                String normalizedKey = LicenseValidator.normalizeKey(licenseKey);
                if (!LicenseValidator.validateFormat(normalizedKey)) {
                    if (callback != null) callback.onError("INVALID_FORMAT", "密钥格式不正确");
                    return;
                }

                String fingerprint = DeviceFingerprint.compute(context);
                String deviceName = DeviceFingerprint.getDeviceName();
                String androidId = DeviceFingerprint.getAndroidId(context);
                String appVersion = BuildConfig.VERSION_NAME;

                JSONObject response = LicenseApiClient.activate(
                        normalizedKey, fingerprint, deviceName, androidId, appVersion);

                if (response.optBoolean("success", false)) {
                    LicenseInfo info = new LicenseInfo(
                            response.getString("license_id"),
                            response.getString("device_id"),
                            response.optString("plan", "lifetime"),
                            System.currentTimeMillis(),
                            System.currentTimeMillis(),
                            response.getString("verification_token")
                    );
                    LicenseStore.save(info, normalizedKey, context);

                    if (callback != null) callback.onSuccess(info);
                } else {
                    String error = response.optString("error", "UNKNOWN_ERROR");
                    if (callback != null) callback.onError(error, errorToMessage(error));
                }
            } catch (Exception e) {
                Log.e(TAG, "Activation failed", e);
                if (callback != null) callback.onError("NETWORK_ERROR", "网络连接失败: " + e.getMessage());
            }
        }, "LicenseActivate").start();
    }

    // ---- 验证 ----

    public void verify(Context context, LicenseCallback callback) {
        new Thread(() -> {
            try {
                String licenseKey = LicenseStore.loadLicenseKey(context);
                LicenseInfo info = LicenseStore.load(context);
                if (licenseKey == null || info == null) {
                    if (callback != null) callback.onError("NO_LICENSE", "未找到许可证");
                    return;
                }

                String fingerprint = DeviceFingerprint.compute(context);
                JSONObject response = LicenseApiClient.verify(
                        licenseKey, fingerprint, info.verificationToken);

                if (response.optBoolean("valid", false)) {
                    String newToken = response.optString("new_verification_token", info.verificationToken);
                    LicenseStore.updateVerification(newToken, context);
                    LicenseInfo updated = LicenseStore.load(context);
                    if (callback != null) callback.onSuccess(updated);
                } else {
                    String error = response.optString("error", "UNKNOWN");
                    if (callback != null) callback.onError(error, errorToMessage(error));
                }
            } catch (Exception e) {
                Log.d(TAG, "Verification failed (offline?): " + e.getMessage());
                if (callback != null) callback.onError("NETWORK_ERROR", e.getMessage());
            }
        }, "LicenseVerify").start();
    }

    // ---- 静默定期验证 ----

    public void verifyIfNeeded(Context context) {
        if (!isLicensed(context)) return;

        LicenseInfo info = LicenseStore.load(context);
        if (info == null) return;

        long elapsed = System.currentTimeMillis() - info.lastVerifiedAt;
        if (elapsed < VERIFY_INTERVAL) return;

        verify(context, new LicenseCallback() {
            @Override
            public void onSuccess(LicenseInfo info) {
                Log.d(TAG, "Silent verification succeeded");
            }

            @Override
            public void onError(String code, String message) {
                Log.d(TAG, "Silent verification skipped: " + code);
            }
        });
    }

    // ---- 解绑 ----

    public void deactivate(Context context, LicenseCallback callback) {
        new Thread(() -> {
            try {
                String licenseKey = LicenseStore.loadLicenseKey(context);
                LicenseInfo info = LicenseStore.load(context);
                if (licenseKey == null || info == null) {
                    if (callback != null) callback.onError("NO_LICENSE", "未找到许可证");
                    return;
                }

                String fingerprint = DeviceFingerprint.compute(context);
                LicenseApiClient.deactivate(licenseKey, fingerprint, info.verificationToken);
                LicenseStore.clear(context);
                if (callback != null) callback.onSuccess(null);
            } catch (Exception e) {
                LicenseStore.clear(context);
                if (callback != null) callback.onError("NETWORK_ERROR", e.getMessage());
            }
        }, "LicenseDeactivate").start();
    }

    // ---- 工具方法 ----

    private static String errorToMessage(String error) {
        switch (error) {
            case "INVALID_KEY": return "无效的许可证密钥";
            case "KEY_SUSPENDED": return "许可证已被暂停";
            case "MAX_DEVICES_REACHED": return "已达到最大设备数限制";
            case "DEVICE_MISMATCH": return "设备不匹配";
            case "TOKEN_MISMATCH": return "验证令牌不匹配";
            case "RATE_LIMITED": return "请求过于频繁，请稍后再试";
            default: return "未知错误: " + error;
        }
    }
}
