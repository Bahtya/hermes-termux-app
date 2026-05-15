package com.termux.app.hermes.update;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.app.hermes.HermesConfigActivity;

public class AppUpdateNotifier {

    static final String CHANNEL_ID = "hermux_app_update";
    private static final int UPDATE_AVAILABLE_ID = 3001;
    static final int DOWNLOAD_PROGRESS_ID = 3002;
    private static final int DOWNLOAD_COMPLETE_ID = 3003;

    private AppUpdateNotifier() {}

    static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }
    }

    static void showUpdateNotification(Context context, AppUpdateInfo info) {
        createNotificationChannel(context);

        Intent intent = new Intent(context, HermesConfigActivity.class);
        intent.setAction("com.hermux.SHOW_UPDATE");
        intent.putExtra("versionCode", info.versionCode);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text,
                        info.versionName))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(UPDATE_AVAILABLE_ID, builder.build());
    }

    static NotificationCompat.Builder buildProgressNotification(Context context,
            int percent, long downloaded, long total) {
        createNotificationChannel(context);

        String text = total > 0
                ? context.getString(R.string.update_download_progress, percent)
                : context.getString(R.string.update_downloading);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(context.getString(R.string.update_downloading))
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }

    static void showDownloadCompleteNotification(Context context, java.io.File apkFile) {
        createNotificationChannel(context);

        Intent installIntent = new Intent(context, AppUpdateService.class);
        installIntent.setAction(AppUpdateService.ACTION_INSTALL);
        installIntent.putExtra("apkPath", apkFile.getAbsolutePath());
        PendingIntent pi = PendingIntent.getService(context, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(R.drawable.ic_service_notification,
                        context.getString(R.string.update_install), pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(DOWNLOAD_PROGRESS_ID);
        nm.notify(DOWNLOAD_COMPLETE_ID, builder.build());
    }
}
