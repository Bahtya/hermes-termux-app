package com.termux.app.hermes.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

public class AppUpdateInstaller {

    private AppUpdateInstaller() {}

    static void installApk(Context context, File apkFile) {
        if (!apkFile.exists()) return;

        // Check install permission on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            permIntent.setData(Uri.parse("package:" + context.getPackageName()));
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(permIntent);
            return;
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".update.fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback: try file:// URI for older devices
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }
}
