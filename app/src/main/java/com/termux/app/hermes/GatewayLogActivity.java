package com.termux.app.hermes;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import androidx.core.content.ContextCompat;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GatewayLogActivity extends AppCompatActivity {

    private static final String LOG_FILE_PATH =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/logs/gateway.log";
    private static final int MAX_LINES = 200;

    private TextView mLogText;
    private ScrollView mScrollView;
    private LinearLayout mSessionList;
    private TextView mCurrentSessionText;
    private Runnable mUptimeUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.gateway_log_title);
        }

        // --- Session History Section ---
        TextView sessionHeader = new TextView(this);
        sessionHeader.setText(R.string.gateway_session_history_title);
        sessionHeader.setTypeface(null, Typeface.BOLD);
        sessionHeader.setPadding(dp(12), dp(12), dp(12), dp(4));
        sessionHeader.setTextSize(16);
        layout.addView(sessionHeader);

        // Current session indicator
        mCurrentSessionText = new TextView(this);
        mCurrentSessionText.setPadding(dp(16), dp(4), dp(16), dp(4));
        mCurrentSessionText.setTextSize(13);
        layout.addView(mCurrentSessionText);

        // Session history list container
        mSessionList = new LinearLayout(this);
        mSessionList.setOrientation(LinearLayout.VERTICAL);
        mSessionList.setPadding(dp(16), dp(0), dp(16), dp(4));
        layout.addView(mSessionList);

        // Clear history button
        TextView clearHistoryBtn = new TextView(this);
        clearHistoryBtn.setText(R.string.gateway_session_clear_title);
        clearHistoryBtn.setPadding(dp(16), dp(4), dp(16), dp(8));
        clearHistoryBtn.setOnClickListener(v -> showClearHistoryConfirm());
        layout.addView(clearHistoryBtn);

        // Separator
        View separator = new View(this);
        separator.setBackgroundColor(ContextCompat.getColor(this, R.color.hermes_separator));
        LinearLayout.LayoutParams sepParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sepParams.setMargins(dp(12), dp(4), dp(12), dp(4));
        separator.setLayoutParams(sepParams);
        layout.addView(separator);

        // --- Log Section ---
        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);

        mLogText = new TextView(this);
        mLogText.setPadding(dp(12), dp(12), dp(12), dp(12));
        mLogText.setTextSize(11);
        mLogText.setTypeface(Typeface.MONOSPACE);
        mLogText.setTextIsSelectable(true);

        mScrollView.addView(mLogText);
        layout.addView(mScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView refreshBtn = new TextView(this);
        refreshBtn.setText(R.string.gateway_log_refresh);
        refreshBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        refreshBtn.setOnClickListener(v -> {
            loadLog();
            loadSessionHistory();
        });
        buttonBar.addView(refreshBtn);

        TextView clearBtn = new TextView(this);
        clearBtn.setText(R.string.gateway_log_clear);
        clearBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        clearBtn.setOnClickListener(v -> showClearConfirm());
        buttonBar.addView(clearBtn);

        layout.addView(buttonBar);
        setContentView(layout);

        loadLog();
        loadSessionHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessionHistory();
        startUptimeUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUptimeUpdates();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================================
    // Session history
    // =========================================================================

    private void loadSessionHistory() {
        HermesConfigManager config = HermesConfigManager.getInstance();
        List<HermesConfigManager.Session> sessions = config.getSessionHistory(this);

        // Update current session indicator
        updateCurrentSession();

        // Build history list
        mSessionList.removeAllViews();
        if (sessions.isEmpty()) {
            TextView emptyText = new TextView(this);
            emptyText.setText(R.string.gateway_session_empty);
            emptyText.setTextSize(12);
            emptyText.setPadding(0, dp(4), 0, dp(4));
            mSessionList.addView(emptyText);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        // Show sessions newest first
        for (int i = sessions.size() - 1; i >= 0; i--) {
            HermesConfigManager.Session session = sessions.get(i);
            TextView sessionView = new TextView(this);
            sessionView.setTextSize(12);
            sessionView.setPadding(0, dp(2), 0, dp(2));

            String startStr = sdf.format(new Date(session.startTime));
            String stopStr = session.stopTime > 0
                    ? sdf.format(new Date(session.stopTime))
                    : getString(R.string.gateway_session_running);
            String durationStr = HermesGatewayService.formatDuration(session.duration);

            sessionView.setText(
                    getString(R.string.gateway_session_start, startStr) + "\n"
                            + getString(R.string.gateway_session_duration, durationStr));
            mSessionList.addView(sessionView);
        }
    }

    private void updateCurrentSession() {
        if (HermesGatewayService.isRunning()) {
            String uptime = HermesGatewayService.getFormattedUptime();
            mCurrentSessionText.setText(
                    getString(R.string.gateway_session_current) + ": "
                            + getString(R.string.gateway_session_running)
                            + " (" + uptime + ")");
            mCurrentSessionText.setVisibility(View.VISIBLE);
        } else {
            mCurrentSessionText.setVisibility(View.GONE);
        }
    }

    private void startUptimeUpdates() {
        stopUptimeUpdates();
        mUptimeUpdater = new Runnable() {
            @Override
            public void run() {
                if (HermesGatewayService.isRunning()) {
                    updateCurrentSession();
                }
                if (!isFinishing()) {
                    getWindow().getDecorView().postDelayed(this, 1000);
                }
            }
        };
        getWindow().getDecorView().postDelayed(mUptimeUpdater, 1000);
    }

    private void stopUptimeUpdates() {
        if (mUptimeUpdater != null) {
            getWindow().getDecorView().removeCallbacks(mUptimeUpdater);
            mUptimeUpdater = null;
        }
    }

    private void showClearHistoryConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gateway_session_clear_confirm_title)
                .setMessage(R.string.gateway_session_clear_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    HermesConfigManager.getInstance().clearSessionHistory(this);
                    loadSessionHistory();
                    Toast.makeText(this, R.string.gateway_session_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // =========================================================================
    // Log loading
    // =========================================================================

    private void loadLog() {
        new Thread(() -> {
            String content = readLastLines();
            runOnUiThread(() -> {
                mLogText.setText(content.isEmpty()
                        ? getString(R.string.gateway_log_empty)
                        : content);
                mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            });
        }).start();
    }

    private String readLastLines() {
        File file = new File(LOG_FILE_PATH);
        if (!file.exists()) return "";

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            return "Error reading log: " + e.getMessage();
        }

        int start = Math.max(0, lines.size() - MAX_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private void showClearConfirm() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.gateway_log_clear_confirm_title)
                .setMessage(R.string.gateway_log_clear_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> clearLog())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearLog() {
        new Thread(() -> {
            File file = new File(LOG_FILE_PATH);
            if (file.exists()) {
                try (FileWriter writer = new FileWriter(file, false)) {
                    writer.write("");
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Failed to clear log: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return;
                }
            }
            runOnUiThread(() -> {
                mLogText.setText(getString(R.string.gateway_log_empty));
                Toast.makeText(this, R.string.gateway_log_cleared, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
