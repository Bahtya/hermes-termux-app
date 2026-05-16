package com.termux.app.hermes.license;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;

import java.security.MessageDigest;

/**
 * APK 签名验证工具
 * 防止重打包的 APK 绕过许可证检查
 */
public class SignatureVerifier {

    // 开发者签名的 SHA-256 哈希（release 签名证书）
    // 部署时需要替换为实际的签名证书哈希
    private static final String EXPECTED_SIGNING_CERT_HASH =
            "b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1";

    private SignatureVerifier() {}

    /**
     * 获取当前 APK 签名证书的 SHA-256 哈希
     */
    public static byte[] getSigningCertHash(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) return null;
            Signature signature = info.signatures[0];
            return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验证 APK 是否使用了预期的签名证书
     * @return true = 签名匹配（正版 APK），false = 签名不匹配（重打包）
     */
    public static boolean verifySignature(Context context) {
        byte[] hash = getSigningCertHash(context);
        if (hash == null) return false;
        String hashHex = bytesToHex(hash);
        return EXPECTED_SIGNING_CERT_HASH.equalsIgnoreCase(hashHex);
    }

    /**
     * 获取签名证书哈希的 Base64 编码（用于日志/调试）
     */
    public static String getSigningCertHashBase64(Context context) {
        byte[] hash = getSigningCertHash(context);
        return hash != null ? Base64.encodeToString(hash, Base64.NO_WRAP) : "unknown";
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
