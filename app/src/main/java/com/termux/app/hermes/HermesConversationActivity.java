package com.termux.app.hermes;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HermesConversationActivity extends AppCompatActivity {

    private static final String CONVERSATIONS_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/conversations";
    private static final String GATEWAY_LOG_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/logs";

    private LinearLayout mListLayout;
    private ProgressBar mProgressBar;
    private Spinner mFilterSpinner;
    private String mCurrentFilter = "all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(this);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.conversation_title);
        }
        root.addView(toolbar);

        // Filter bar
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        filterBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView filterLabel = new TextView(this);
        filterLabel.setText(R.string.conversation_filter_label);
        filterLabel.setPadding(0, 0, dp(8), 0);
        filterBar.addView(filterLabel);

        mFilterSpinner = new Spinner(this);
        String[] filters = getResources().getStringArray(R.array.conversation_filter_names);
        mFilterSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, filters));
        mFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                mCurrentFilter = new String[]{"all", "today", "week", "month"}[pos];
                loadConversations();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        filterBar.addView(mFilterSpinner);
        root.addView(filterBar);

        mProgressBar = new ProgressBar(this);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);
        root.addView(mProgressBar);

        androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
        mListLayout = new LinearLayout(this);
        mListLayout.setOrientation(LinearLayout.VERTICAL);
        mListLayout.setPadding(dp(16), 0, dp(16), dp(16));
        scrollView.addView(mListLayout);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, scrollParams);

        setContentView(root);
        loadConversations();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadConversations() {
        mProgressBar.setVisibility(View.VISIBLE);
        mListLayout.removeAllViews();

        new Thread(() -> {
            List<ConvEntry> entries = new ArrayList<>();

            // Scan conversations directory
            File convDir = new File(CONVERSATIONS_DIR);
            if (convDir.exists() && convDir.isDirectory()) {
                scanDirectory(convDir, entries);
            }

            // Also scan gateway logs for message patterns
            File logDir = new File(GATEWAY_LOG_DIR);
            if (logDir.exists() && logDir.isDirectory()) {
                scanLogDirectory(logDir, entries);
            }

            // Sort by timestamp descending
            Collections.sort(entries, (a, b) -> Long.compare(b.timestamp, a.timestamp));

            // Apply filter
            long now = System.currentTimeMillis();
            List<ConvEntry> filtered = new ArrayList<>();
            for (ConvEntry e : entries) {
                long age = now - e.timestamp;
                switch (mCurrentFilter) {
                    case "today":
                        if (age < 86_400_000) filtered.add(e);
                        break;
                    case "week":
                        if (age < 604_800_000) filtered.add(e);
                        break;
                    case "month":
                        if (age < 2_592_000_000L) filtered.add(e);
                        break;
                    default:
                        filtered.add(e);
                        break;
                }
            }

            runOnUiThread(() -> {
                mProgressBar.setVisibility(View.GONE);
                if (filtered.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.conversation_empty);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(48), 0, 0);
                    mListLayout.addView(empty);
                } else {
                    String lastDate = "";
                    for (ConvEntry e : filtered) {
                        String dateStr = formatDate(e.timestamp);
                        if (!dateStr.equals(lastDate)) {
                            TextView dateHeader = new TextView(this);
                            dateHeader.setText(dateStr);
                            dateHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                            dateHeader.setPadding(0, dp(8), 0, dp(4));
                            mListLayout.addView(dateHeader);
                            lastDate = dateStr;
                        }
                        addConversationItem(e);
                    }
                }
            });
        }).start();
    }

    private void scanDirectory(File dir, List<ConvEntry> entries) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, entries);
            } else if (file.getName().endsWith(".json") || file.getName().endsWith(".jsonl")
                    || file.getName().endsWith(".log")) {
                entries.add(new ConvEntry(file.getName(), file.getAbsolutePath(),
                        file.lastModified(), file.length(), guessPlatform(file)));
            }
        }
    }

    private void scanLogDirectory(File dir, List<ConvEntry> entries) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.getName().endsWith(".log")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    long msgCount = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("\"role\":\"user\"") || line.contains("\"role\":\"assistant\"")) {
                            msgCount++;
                        }
                    }
                    if (msgCount > 0) {
                        entries.add(new ConvEntry(
                                getString(R.string.conversation_gateway_log, msgCount),
                                file.getAbsolutePath(),
                                file.lastModified(),
                                file.length(),
                                "gateway"));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void addConversationItem(ConvEntry entry) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, dp(4), 0, dp(4));

        TextView title = new TextView(this);
        title.setText(entry.name);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(14);
        item.addView(title);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);

        TextView time = new TextView(this);
        time.setText(formatTime(entry.timestamp));
        time.setTextSize(12);
        details.addView(time);

        TextView sep = new TextView(this);
        sep.setText(" · ");
        sep.setTextSize(12);
        details.addView(sep);

        TextView platform = new TextView(this);
        platform.setText(entry.platform);
        platform.setTextSize(12);
        details.addView(platform);

        TextView sep2 = new TextView(this);
        sep2.setText(" · ");
        sep2.setTextSize(12);
        details.addView(sep2);

        TextView size = new TextView(this);
        size.setText(formatSize(entry.size));
        size.setTextSize(12);
        details.addView(size);

        item.addView(details);

        // Add divider
        View divider = new View(this);
        divider.setBackgroundColor(0x1F000000);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.topMargin = dp(4);
        divider.setLayoutParams(divParams);
        item.addView(divider);

        mListLayout.addView(item);
    }

    private String guessPlatform(File file) {
        String name = file.getName().toLowerCase();
        if (name.contains("feishu") || name.contains("lark")) return "Feishu";
        if (name.contains("telegram")) return "Telegram";
        if (name.contains("discord")) return "Discord";
        if (name.contains("whatsapp")) return getString(R.string.conversation_platform_whatsapp);
        return getString(R.string.conversation_platform_general);
    }

    private String formatDate(long millis) {
        Date date = new Date(millis);
        long now = System.currentTimeMillis();
        long diff = now - millis;
        if (diff < 86_400_000) return getString(R.string.conversation_date_today);
        if (diff < 2 * 86_400_000) return getString(R.string.conversation_date_yesterday);
        return new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date);
    }

    private String formatTime(long millis) {
        return DateFormat.getTimeFormat(this).format(new Date(millis));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static class ConvEntry {
        final String name;
        final String path;
        final long timestamp;
        final long size;
        final String platform;

        ConvEntry(String name, String path, long timestamp, long size, String platform) {
            this.name = name;
            this.path = path;
            this.timestamp = timestamp;
            this.size = size;
            this.platform = platform;
        }
    }
}
