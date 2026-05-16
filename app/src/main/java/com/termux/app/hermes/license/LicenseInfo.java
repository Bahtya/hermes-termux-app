package com.termux.app.hermes.license;

/**
 * 许可证状态数据类（不可变）
 */
public class LicenseInfo {

    public final String licenseId;
    public final String deviceId;
    public final String plan;
    public final long activatedAt;
    public final long lastVerifiedAt;
    public final String verificationToken;

    public LicenseInfo(String licenseId, String deviceId, String plan,
                       long activatedAt, long lastVerifiedAt, String verificationToken) {
        this.licenseId = licenseId;
        this.deviceId = deviceId;
        this.plan = plan;
        this.activatedAt = activatedAt;
        this.lastVerifiedAt = lastVerifiedAt;
        this.verificationToken = verificationToken;
    }

    public boolean isActivated() {
        return licenseId != null && !licenseId.isEmpty();
    }
}
