package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
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
    private static final int MAX_LINES = 500;

    private TextView mLogText;
    private ScrollView mScrollView;
    private EditText mSearchBar;
    private TextView mLineCount;
    private String mFullLogContent = "";
    private String mCurrentFilter = "";
    private boolean mAutoScroll = true;

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

        // Search bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(8), dp(4), dp(8), dp(0));
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        mSearchBar = new EditText(this);
        mSearchBar.setHint(R.string.gateway_log_search_hint);
        mSearchBar.setSingleLine(true);
        mSearchBar.setTextSize(14);
        mSearchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mCurrentFilter = s.toString().trim().toLowerCase();
                applyFilter();
            }
        });
        searchRow.addView(mSearchBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mLineCount = new TextView(this);
        mLineCount.setTextSize(11);
        mLineCount.setPadding(dp(8), 0, 0, 0);
        searchRow.addView(mLineCount);

        layout.addView(searchRow);

        // Log scroll view
        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            View child = v.getChildAt(v.getChildCount() - 1);
            if (child != null) {
                int diff = child.getBottom() - (v.getHeight() + scrollY);
                mAutoScroll = diff < dp(50);
            }
        });

        mLogText = new TextView(this);
        mLogText.setPadding(dp(12), dp(12), dp(12), dp(12));
        mLogText.setTextSize(11);
        mLogText.setTypeface(android.graphics.Typeface.MONOSPACE);
        mLogText.setTextIsSelectable(true);

        mScrollView.addView(mLogText);
        layout.addView(mScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Button bar
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(dp(8), dp(4), dp(8), dp(8));
        buttonBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView refreshBtn = makeButton(R.string.gateway_log_refresh);
        refreshBtn.setOnClickListener(v -> loadLog());
        buttonBar.addView(refreshBtn);

        TextView shareBtn = makeButton(R.string.gateway_log_share);
        shareBtn.setOnClickListener(v -> shareLog());
        buttonBar.addView(shareBtn);

        TextView copyBtn = makeButton(R.string.gateway_log_copy);
        copyBtn.setOnClickListener(v -> copyLog());
        buttonBar.addView(copyBtn);

        TextView clearBtn = makeButton(R.string.gateway_log_clear);
        clearBtn.setOnClickListener(v -> showClearConfirm());
        buttonBar.addView(clearBtn);

        layout.addView(buttonBar);
        setContentView(layout);

        loadLog();
    }

    private TextView makeButton(int textResId) {
        TextView btn = new TextView(this);
        btn.setText(textResId);
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setTextSize(13);
        return btn;
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
            mFullLogContent = readLastLines();
            runOnUiThread(() -> {
                applyFilter();
                if (mAutoScroll) {
                    mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }).start();
    }

    private void applyFilter() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = mFullLogContent.split("\n");
        int matchCount = 0;

        for (String line : lines) {
            boolean matches = mCurrentFilter.isEmpty()
                    || line.toLowerCase().contains(mCurrentFilter);
            if (matches) {
                matchCount++;
                SpannableString span = colorizeLine(line);
                sb.append(span).append("\n");
            }
        }

        if (sb.length() == 0) {
            mLogText.setText(getString(R.string.gateway_log_empty));
            mLineCount.setText("");
        } else {
            mLogText.setText(sb);
            mLineCount.setText(matchCount + " lines");
        }
    }

    private SpannableString colorizeLine(String line) {
        SpannableString span = new SpannableString(line);
        String lower = line.toLowerCase();

        if (lower.contains("error") || lower.contains("err:") || lower.contains("fatal")) {
            span.setSpan(new ForegroundColorSpan(Color.parseColor("#FF6B6B")), 0, line.length(), 0);
        } else if (lower.contains("warn") || lower.contains("warning")) {
            span.setSpan(new ForegroundColorSpan(Color.parseColor("#FFD93D")), 0, line.length(), 0);
        } else if (lower.contains("info") || lower.contains("started") || lower.contains("listening")) {
            span.setSpan(new ForegroundColorSpan(Color.parseColor("#6BCB77")), 0, line.length(), 0);
        }

        return span;
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

    private void shareLog() {
        String content = mFullLogContent.isEmpty()
                ? getString(R.string.gateway_log_empty) : mFullLogContent;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Hermes Gateway Log");
        shareIntent.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.gateway_log_share)));
    }

    private void copyLog() {
        String content = mFullLogContent.isEmpty()
                ? getString(R.string.gateway_log_empty) : mFullLogContent;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Gateway Log", content);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.gateway_log_copied, Toast.LENGTH_SHORT).show();
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
            mFullLogContent = "";
            runOnUiThread(() -> {
                mLogText.setText(getString(R.string.gateway_log_empty));
                mLineCount.setText("");
                Toast.makeText(this, R.string.gateway_log_cleared, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
