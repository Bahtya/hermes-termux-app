package com.termux.app.hermes.license;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * 许可证门禁工具类
 * 供其他 Activity 调用来检查许可证状态
 */
public class LicenseGate {

    private static final String TAG = "LicenseGate";

    private LicenseGate() {}

    /**
     * 检查许可证状态，如果未激活则显示激活界面。
     * 包含 APK 签名验证防重打包。
     * @return true = 已激活可以继续，false = 正在跳转到激活界面
     */
    public static boolean checkOrShowGate(Activity activity) {
        if (activity == null) return false;

        // APK 签名验证：重打包的 APK 签名不同，强制要求重新激活
        if (!SignatureVerifier.verifySignature(activity)) {
            Log.w(TAG, "APK signature verification failed — possible repackaging");
            // 清除本地许可证（HMAC 也已失效），强制重新激活
            LicenseStore.clear(activity);
            showActivation(activity, "应用签名验证失败，请从官方渠道下载");
            return false;
        }

        LicenseManager lm = LicenseManager.getInstance();

        // 检查完整性
        if (!LicenseStore.validateIntegrity(activity)) {
            showActivation(activity, "许可证数据异常，请重新激活");
            return false;
        }

        // 未激活
        if (!lm.isLicensed(activity)) {
            showActivation(activity, null);
            return false;
        }

        // 超过14天离线，锁定
        if (lm.isExpired(activity)) {
            showActivation(activity, "许可证验证已过期，请连接网络后重新激活");
            return false;
        }

        // 宽限期警告（7-14天）
        if (lm.isGracePeriod(activity)) {
            int daysOffline = lm.getDaysOffline(activity);
            Toast.makeText(activity,
                    "许可证验证已离线 " + daysOffline + " 天，请尽快连接网络",
                    Toast.LENGTH_LONG).show();
        }

        return true;
    }

    private static void showActivation(Activity activity, String message) {
        Intent intent = new Intent(activity, LicenseActivationActivity.class);
        if (message != null) {
            intent.putExtra(LicenseActivationActivity.EXTRA_MESSAGE, message);
        }
        activity.startActivity(intent);
    }
}
