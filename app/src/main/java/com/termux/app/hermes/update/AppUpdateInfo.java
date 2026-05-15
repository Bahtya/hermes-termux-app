package com.termux.app.hermes.update;

import org.json.JSONArray;
import org.json.JSONObject;

public class AppUpdateInfo {

    public final String versionName;
    public final int versionCode;
    public final int minSupportedVersionCode;
    public final String downloadUrl;
    public final String downloadUrlMirror;
    public final String sha256;
    public final long fileSize;
    public final String releaseNotes;
    public final String releaseNotesZh;
    public final boolean forceUpdate;

    private AppUpdateInfo(Builder b) {
        this.versionName = b.versionName;
        this.versionCode = b.versionCode;
        this.minSupportedVersionCode = b.minSupportedVersionCode;
        this.downloadUrl = b.downloadUrl;
        this.downloadUrlMirror = b.downloadUrlMirror;
        this.sha256 = b.sha256;
        this.fileSize = b.fileSize;
        this.releaseNotes = b.releaseNotes;
        this.releaseNotesZh = b.releaseNotesZh;
        this.forceUpdate = b.forceUpdate;
    }

    boolean isNewerThan(int currentVersionCode) {
        return versionCode > currentVersionCode;
    }

    boolean isForcedFor(int currentVersionCode) {
        return forceUpdate || currentVersionCode < minSupportedVersionCode;
    }

    String getDisplayNotes(String language) {
        if (language.startsWith("zh") && releaseNotesZh != null && !releaseNotesZh.isEmpty()) {
            return releaseNotesZh;
        }
        return releaseNotes != null ? releaseNotes : "";
    }

    /**
     * Parse from Supabase PostgREST response (direct JSON array):
     * [{ "version_name": "1.5.0", "version_code": 200, ... }]
     */
    static AppUpdateInfo fromSupabaseJson(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        if (arr.length() == 0) return null;
        JSONObject obj = arr.getJSONObject(0);

        Builder b = new Builder();
        b.versionName = obj.optString("version_name", "");
        b.versionCode = obj.optInt("version_code", 0);
        b.minSupportedVersionCode = obj.optInt("min_supported_version_code", 0);
        b.downloadUrl = obj.optString("download_url", "");
        b.downloadUrlMirror = obj.optString("download_url_mirror", "");
        b.sha256 = obj.optString("sha256", "");
        b.fileSize = obj.optLong("file_size", 0);
        b.releaseNotes = obj.optString("release_notes", "");
        b.releaseNotesZh = obj.optString("release_notes_zh", "");
        b.forceUpdate = obj.optBoolean("force_update", false);
        return b.build();
    }

    /**
     * Parse from GitHub fallback JSON:
     * { "latest": { "versionName": "...", "versionCode": 200, ... } }
     */
    static AppUpdateInfo fromFallbackJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject latest = root.optJSONObject("latest");
        if (latest == null) return null;

        Builder b = new Builder();
        b.versionName = latest.optString("versionName", "");
        b.versionCode = latest.optInt("versionCode", 0);
        b.minSupportedVersionCode = latest.optInt("minSupportedVersionCode", 0);
        b.downloadUrl = latest.optString("downloadUrl", "");
        b.downloadUrlMirror = latest.optString("downloadUrlMirror", "");
        b.sha256 = latest.optString("sha256", "");
        b.fileSize = latest.optLong("fileSize", 0);
        b.releaseNotes = latest.optString("releaseNotes", "");
        b.releaseNotesZh = latest.optString("releaseNotesZh", "");
        b.forceUpdate = latest.optBoolean("forceUpdate", false);
        return b.build();
    }

    private static class Builder {
        String versionName = "";
        int versionCode;
        int minSupportedVersionCode;
        String downloadUrl = "";
        String downloadUrlMirror = "";
        String sha256 = "";
        long fileSize;
        String releaseNotes = "";
        String releaseNotesZh = "";
        boolean forceUpdate;

        AppUpdateInfo build() {
            return new AppUpdateInfo(this);
        }
    }
}
