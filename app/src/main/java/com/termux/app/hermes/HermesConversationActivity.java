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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HermesConversationActivity extends AppCompatActivity {

    private static final String SESSION_TOKEN_HEADER = "X-Hermes-Session-Token";

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

    private int getAgentPort() {
        if (HermesWebActivity.sDetectedPort != null) {
            try { return Integer.parseInt(HermesWebActivity.sDetectedPort); }
            catch (NumberFormatException ignored) {}
        }
        return HermesWebActivity.DEFAULT_PORT;
    }

    private String getSessionToken() {
        return HermesWebActivity.sSessionToken;
    }

    private void loadConversations() {
        mProgressBar.setVisibility(View.VISIBLE);
        mListLayout.removeAllViews();

        new Thread(() -> {
            List<ConvEntry> entries = fetchSessionsFromApi();

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

    private List<ConvEntry> fetchSessionsFromApi() {
        List<ConvEntry> entries = new ArrayList<>();
        int port = getAgentPort();
        String token = getSessionToken();

        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/api/sessions").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty(SESSION_TOKEN_HEADER, token);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return entries;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            JSONArray sessions = new JSONArray(sb.toString());
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.getJSONObject(i);
                String id = s.optString("id", "");
                String name = s.optString("name", s.optString("title", id));
                long ts = s.optLong("updated_at", s.optLong("created_at", 0));
                if (ts == 0) ts = System.currentTimeMillis();
                int msgCount = s.optInt("message_count", 0);
                String platform = s.optString("platform", s.optString("source", ""));
                if (platform.isEmpty()) platform = guessPlatformFromId(id);
                entries.add(new ConvEntry(name, id, ts, msgCount, platform));
            }

            Collections.sort(entries, (a, b) -> Long.compare(b.timestamp, a.timestamp));
        } catch (Exception ignored) {}

        return entries;
    }

    private String guessPlatformFromId(String id) {
        String lower = id.toLowerCase();
        if (lower.contains("feishu") || lower.contains("lark")) return "Feishu";
        if (lower.contains("telegram")) return "Telegram";
        if (lower.contains("discord")) return "Discord";
        if (lower.contains("whatsapp")) return getString(R.string.conversation_platform_whatsapp);
        return getString(R.string.conversation_platform_general);
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

        if (entry.size > 0) {
            TextView sep2 = new TextView(this);
            sep2.setText(" · ");
            sep2.setTextSize(12);
            details.addView(sep2);

            TextView msgCount = new TextView(this);
            msgCount.setText(entry.size + " msgs");
            msgCount.setTextSize(12);
            details.addView(msgCount);
        }

        item.addView(details);

        View divider = new View(this);
        divider.setBackgroundColor(0x1F000000);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.topMargin = dp(4);
        divider.setLayoutParams(divParams);
        item.addView(divider);

        mListLayout.addView(item);
    }

    private String formatDate(long millis) {
        long now = System.currentTimeMillis();
        long diff = now - millis;
        if (diff < 86_400_000) return getString(R.string.conversation_date_today);
        if (diff < 2 * 86_400_000) return getString(R.string.conversation_date_yesterday);
        return new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(new Date(millis));
    }

    private String formatTime(long millis) {
        return DateFormat.getTimeFormat(this).format(new Date(millis));
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
