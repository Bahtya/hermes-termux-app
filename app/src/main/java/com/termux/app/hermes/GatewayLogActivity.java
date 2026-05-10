package com.termux.app.hermes;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GatewayLogActivity extends AppCompatActivity {

    private static final String LOG_FILE_PATH =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/logs/gateway.log";
    private static final int MAX_LINES = 200;

    private TextView mLogText;
    private ScrollView mScrollView;
    private EditText mSearchBar;
    private String mAllLogContent = "";
    private String mCurrentFilter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.gateway_log_title);
            String level = HermesConfigManager.getInstance().getLogLevel();
            actionBar.setSubtitle(getString(R.string.log_level_title) + ": " + level);
        }

        // Search bar
        mSearchBar = new EditText(this);
        mSearchBar.setHint(getString(R.string.gateway_log_search_hint));
        mSearchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        mSearchBar.setTextSize(14);
        mSearchBar.setSingleLine(true);
        mSearchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mCurrentFilter = s.toString().trim().toLowerCase();
                applyFilter();
            }
        });
        layout.addView(mSearchBar);

        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);

        mLogText = new TextView(this);
        mLogText.setPadding(dp(12), dp(12), dp(12), dp(12));
        mLogText.setTextSize(11);
        mLogText.setTypeface(android.graphics.Typeface.MONOSPACE);
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
        refreshBtn.setOnClickListener(v -> loadLog());
        buttonBar.addView(refreshBtn);

        TextView clearBtn = new TextView(this);
        clearBtn.setText(R.string.gateway_log_clear);
        clearBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        clearBtn.setOnClickListener(v -> showClearConfirm());
        buttonBar.addView(clearBtn);

        TextView shareBtn = new TextView(this);
        shareBtn.setText(R.string.gateway_log_share);
        shareBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        shareBtn.setOnClickListener(v -> shareLog());
        buttonBar.addView(shareBtn);

        layout.addView(buttonBar);
        setContentView(layout);

        loadLog();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadLog() {
        new Thread(() -> {
            String content = readLastLines();
            runOnUiThread(() -> {
                mAllLogContent = content;
                applyFilter();
                mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
            });
        }).start();
    }

    private void applyFilter() {
        if (mAllLogContent.isEmpty()) {
            mLogText.setText(getString(R.string.gateway_log_empty));
            return;
        }
        if (mCurrentFilter.isEmpty()) {
            mLogText.setText(mAllLogContent);
            return;
        }
        String[] lines = mAllLogContent.split("\n");
        StringBuilder filtered = new StringBuilder();
        for (String line : lines) {
            if (line.toLowerCase().contains(mCurrentFilter)) {
                filtered.append(line).append("\n");
            }
        }
        mLogText.setText(filtered.length() > 0 ? filtered.toString() : "No matches for \"" + mCurrentFilter + "\"");
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
                mAllLogContent = "";
                applyFilter();
                Toast.makeText(this, R.string.gateway_log_cleared, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void shareLog() {
        CharSequence text = mLogText.getText();
        if (text == null || text.toString().equals(getString(R.string.gateway_log_empty))) {
            Toast.makeText(this, R.string.gateway_log_share_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.gateway_log_share_title));
        shareIntent.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.gateway_log_share_title)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
