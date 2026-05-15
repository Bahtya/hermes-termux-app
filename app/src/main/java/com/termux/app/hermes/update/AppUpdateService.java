package com.termux.app.hermes.update;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

public class AppUpdateService extends Service {

    private static final String TAG = "AppUpdateService";

    public static final String ACTION_DOWNLOAD = "com.hermux.UPDATE_DOWNLOAD";
    public static final String ACTION_INSTALL = "com.hermux.UPDATE_INSTALL";

    private static AppUpdateInfo sPendingUpdate;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mCancelled = false;

    public static void setPendingUpdate(AppUpdateInfo info) {
        sPendingUpdate = info;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppUpdateNotifier.createNotificationChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_INSTALL.equals(action)) {
            String apkPath = intent.getStringExtra("apkPath");
            if (apkPath != null) {
                AppUpdateInstaller.installApk(this, new File(apkPath));
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_DOWNLOAD.equals(action)) {
            if (sPendingUpdate == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startDownload(sPendingUpdate);
            return START_STICKY;
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void startDownload(AppUpdateInfo info) {
        mCancelled = false;

        NotificationCompat.Builder nb = AppUpdateNotifier.buildProgressNotification(
                this, 0, 0, info.fileSize);
        startForeground(AppUpdateNotifier.DOWNLOAD_PROGRESS_ID, nb.build());

        AppUpdateDownloader.downloadApk(this, info, new AppUpdateDownloader.ProgressListener() {
            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                mHandler.post(() -> {
                    NotificationCompat.Builder nb = AppUpdateNotifier.buildProgressNotification(
                            AppUpdateService.this, percent, downloadedBytes, totalBytes);
                    NotificationManager nm = (NotificationManager)
                            getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(AppUpdateNotifier.DOWNLOAD_PROGRESS_ID, nb.build());
                });
            }

            @Override
            public void onComplete(File apkFile) {
                mHandler.post(() -> {
                    stopForeground(true);
                    AppUpdateNotifier.showDownloadCompleteNotification(
                            AppUpdateService.this, apkFile);
                    AppUpdateInstaller.installApk(AppUpdateService.this, apkFile);
                    stopSelf();
                });
            }

            @Override
            public void onError(String message) {
                mHandler.post(() -> {
                    stopForeground(true);
                    Log.e(TAG, "Download failed: " + message);
                    stopSelf();
                });
            }

            @Override
            public boolean isCancelled() {
                return mCancelled;
            }
        });
    }

    @Override
    public void onDestroy() {
        mCancelled = true;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
