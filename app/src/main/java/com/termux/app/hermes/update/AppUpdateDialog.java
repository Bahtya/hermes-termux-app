package com.termux.app.hermes.update;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;
import com.termux.BuildConfig;

import java.util.Locale;

public class AppUpdateDialog {

    private AppUpdateDialog() {}

    public interface Callbacks {
        void onUpdateNow(AppUpdateInfo info);
        void onSkip(int versionCode);
    }

    public static void show(Context context, AppUpdateInfo info, Callbacks callbacks) {
        boolean forced = info.isForcedFor(BuildConfig.VERSION_CODE);
        String notes = info.getDisplayNotes(Locale.getDefault().getLanguage());
        String sizeStr = formatFileSize(info.fileSize);

        ScrollView scrollView = new ScrollView(context);
        TextView content = new TextView(context);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);

        StringBuilder msg = new StringBuilder();
        msg.append(context.getString(R.string.update_available_message,
                info.versionName, BuildConfig.VERSION_NAME));
        if (!sizeStr.isEmpty()) {
            msg.append("\n").append(context.getString(R.string.update_size, sizeStr));
        }
        if (!notes.isEmpty()) {
            msg.append("\n\n").append(notes);
        }
        content.setText(msg.toString());
        content.setTextSize(14);
        scrollView.addView(content);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.update_available_title)
                .setView(scrollView)
                .setPositiveButton(R.string.update_now, (dialog, which) -> {
                    if (callbacks != null) callbacks.onUpdateNow(info);
                });

        if (!forced) {
            builder.setNegativeButton(R.string.update_later, null);
            builder.setNeutralButton(R.string.update_skip, (dialog, which) -> {
                if (callbacks != null) callbacks.onSkip(info.versionCode);
            });
        }

        builder.setCancelable(!forced);
        builder.show();
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
