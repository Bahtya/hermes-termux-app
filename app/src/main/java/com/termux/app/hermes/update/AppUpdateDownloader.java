package com.termux.app.hermes.update;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;

public class AppUpdateDownloader {

    private static final String TAG = "AppUpdateDownloader";
    private static final String APK_FILENAME = "hermux_update.apk";
    private static final int BUFFER_SIZE = 8192;

    interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File apkFile);
        void onError(String message);
        boolean isCancelled();
    }

    static File getApkFile(Context context) {
        File dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir != null && !dir.exists()) dir.mkdirs();
        return new File(dir, APK_FILENAME);
    }

    static void downloadApk(Context context, AppUpdateInfo info, ProgressListener listener) {
        new Thread(() -> {
            File target = getApkFile(context);
            try {
                // Try primary URL first, then mirror
                boolean success = tryDownload(info.downloadUrl, target, info.fileSize, listener)
                        || tryDownload(info.downloadUrlMirror, target, info.fileSize, listener);

                if (listener.isCancelled()) return;

                if (!success) {
                    target.delete();
                    listener.onError("Download failed from all sources");
                    return;
                }

                // Verify SHA-256
                if (info.sha256 != null && !info.sha256.isEmpty()) {
                    String actual = computeSha256(target);
                    if (!info.sha256.equalsIgnoreCase(actual)) {
                        target.delete();
                        listener.onError("SHA-256 verification failed");
                        return;
                    }
                }

                listener.onComplete(target);
            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                target.delete();
                listener.onError(e.getMessage());
            }
        }, "ApkDownload").start();
    }

    private static boolean tryDownload(String url, File target, long expectedSize,
                                        ProgressListener listener) throws Exception {
        if (url == null || url.isEmpty()) return false;

        long existingSize = target.exists() ? target.length() : 0;
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");

            // Resume support
            if (existingSize > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
            }

            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();

            // Handle redirects
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null) {
                    return tryDownload(location, target, expectedSize, listener);
                }
            }

            boolean append = false;
            long totalSize = expectedSize;

            if (code == 206) {
                // Partial content — resume
                append = true;
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) {
                    String[] parts = contentRange.split("/");
                    if (parts.length == 2) {
                        totalSize = Long.parseLong(parts[1]);
                    }
                }
            } else if (code == 200) {
                // Full content — start fresh
                append = false;
                existingSize = 0;
                String contentLength = conn.getHeaderField("Content-Length");
                if (contentLength != null) {
                    totalSize = Long.parseLong(contentLength);
                }
            } else {
                return false;
            }

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(target, append);
            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = existingSize;
            int lastPercent = -1;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                if (listener.isCancelled()) {
                    in.close();
                    out.close();
                    return false;
                }
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                int percent = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : 0;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    listener.onProgress(percent, downloaded, totalSize);
                }
            }

            out.flush();
            out.close();
            in.close();
            return true;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = fis.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        fis.close();

        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
