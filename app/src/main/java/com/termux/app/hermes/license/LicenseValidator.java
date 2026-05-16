package com.termux.app.hermes.license;

/**
 * 客户端许可证密钥格式校验
 * 格式: HRMX-XXXX-XXXX-XXXX-XXXX
 */
public class LicenseValidator {

    private static final String PREFIX = "HRMX-";
    private static final String VALID_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int TOTAL_LENGTH = 24; // HRMX-XXXX-XXXX-XXXX-XXXX

    private LicenseValidator() {}

    /**
     * 校验密钥格式（在发起网络请求前先做本地校验）
     */
    public static boolean validateFormat(String licenseKey) {
        if (licenseKey == null) return false;
        String normalized = normalizeKey(licenseKey);
        if (normalized.length() != TOTAL_LENGTH) return false;
        if (!normalized.startsWith(PREFIX)) return false;

        // 检查4个组: 每个 HRMX- 之后是 XXXX-XXXX-XXXX-XXXX
        for (int i = 5; i < TOTAL_LENGTH; i++) {
            char c = normalized.charAt(i);
            if ((i + 1) % 5 == 0) {
                // 位置 9, 14, 19 应该是 '-'
                if (c != '-') return false;
            } else {
                if (VALID_CHARS.indexOf(c) < 0) return false;
            }
        }
        return true;
    }

    /**
     * 标准化密钥: 去除空格，转大写
     */
    public static String normalizeKey(String input) {
        if (input == null) return "";
        return input.trim().toUpperCase().replaceAll("\\s+", "");
    }
}
