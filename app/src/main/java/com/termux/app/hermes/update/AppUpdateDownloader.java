package com.termux.app.hermes.update;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;

public class AppUpdateDownloader {

    private static final String TAG = "AppUpdateDownloader";
    private static final String APK_FILENAME = "hermux_update.apk";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_REDIRECTS = 5;

    interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File apkFile);
        void onError(String message);
        boolean isCancelled();
    }

    static File getApkFile(Context context) throws Exception {
        File dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            // Fallback to internal storage
            dir = new File(context.getFilesDir(), "download");
        }
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, APK_FILENAME);
    }

    static void downloadApk(Context context, AppUpdateInfo info, ProgressListener listener) {
        new Thread(() -> {
            File target;
            try {
                target = getApkFile(context);
            } catch (Exception e) {
                listener.onError("Storage unavailable");
                return;
            }
            try {
                boolean success = tryDownload(info.downloadUrl, target, info.fileSize, listener, 0)
                        || tryDownload(info.downloadUrlMirror, target, info.fileSize, listener, 0);

                if (listener.isCancelled()) return;

                if (!success) {
                    target.delete();
                    listener.onError("Download failed from all sources");
                    return;
                }

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
                                        ProgressListener listener, int redirectCount) throws Exception {
        if (url == null || url.isEmpty()) return false;
        if (redirectCount > MAX_REDIRECTS) {
            throw new Exception("Too many redirects");
        }

        long existingSize = target.exists() ? target.length() : 0;
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");

            if (existingSize > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
            }

            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();

            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null) {
                    return tryDownload(location, target, expectedSize, listener, redirectCount + 1);
                }
            }

            boolean append = false;
            long totalSize = expectedSize;

            if (code == 206) {
                append = true;
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null) {
                    String[] parts = contentRange.split("/");
                    if (parts.length == 2) {
                        totalSize = Long.parseLong(parts[1]);
                    }
                }
            } else if (code == 200) {
                append = false;
                existingSize = 0;
                String contentLength = conn.getHeaderField("Content-Length");
                if (contentLength != null) {
                    totalSize = Long.parseLong(contentLength);
                }
            } else {
                return false;
            }

            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = conn.getInputStream();
                out = new FileOutputStream(target, append);
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloaded = existingSize;
                int lastPercent = -1;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (listener.isCancelled()) {
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
                return true;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String computeSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
