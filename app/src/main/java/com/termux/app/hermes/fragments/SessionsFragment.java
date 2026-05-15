package com.termux.app.hermes.fragments;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.hermes.HermesWebActivity;

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

public class SessionsFragment extends Fragment {

    private static final String SESSION_TOKEN_HEADER = "X-Hermes-Session-Token";

    private LinearLayout mListLayout;
    private ProgressBar mProgressBar;
    private Spinner mFilterSpinner;
    private String mCurrentFilter = "all";

    private String mLoadError;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sessions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mFilterSpinner = view.findViewById(R.id.filter_spinner);
        mProgressBar = view.findViewById(R.id.progress_bar);
        mListLayout = view.findViewById(R.id.list_layout);

        // Setup filter spinner
        String[] filters = getResources().getStringArray(R.array.conversation_filter_names);
        mFilterSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, filters));
        mFilterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                mCurrentFilter = new String[]{"all", "today", "week", "month"}[pos];
                loadConversations();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        loadConversations();
    }

    private int getAgentPort() {
        if (HermesWebActivity.sDetectedPort != null) {
            try {
                return Integer.parseInt(HermesWebActivity.sDetectedPort);
            } catch (NumberFormatException ignored) {
            }
        }
        return HermesWebActivity.DEFAULT_PORT;
    }

    private String getSessionToken() {
        return HermesWebActivity.sSessionToken;
    }

    private void loadConversations() {
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        if (mListLayout != null) mListLayout.removeAllViews();

        new Thread(() -> {
            mLoadError = null;
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

            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                if (mListLayout == null) return;

                if (mLoadError != null) {
                    TextView error = new TextView(requireContext());
                    error.setText(mLoadError);
                    error.setGravity(android.view.Gravity.CENTER);
                    error.setPadding(0, dp(48), 0, 0);
                    mListLayout.addView(error);
                } else if (filtered.isEmpty()) {
                    TextView empty = new TextView(requireContext());
                    empty.setText(R.string.conversation_empty);
                    empty.setGravity(android.view.Gravity.CENTER);
                    empty.setPadding(0, dp(48), 0, 0);
                    mListLayout.addView(empty);
                } else {
                    String lastDate = "";
                    for (ConvEntry e : filtered) {
                        String dateStr = formatDate(e.timestamp);
                        if (!dateStr.equals(lastDate)) {
                            TextView dateHeader = new TextView(requireContext());
                            dateHeader.setText(dateStr);
                            dateHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                            dateHeader.setTextColor(ContextCompat.getColor(requireContext(),
                                    R.color.hermes_text_primary));
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
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/api/sessions").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty(SESSION_TOKEN_HEADER, token);
            }

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                mLoadError = getString(R.string.conversation_error_auth);
                return entries;
            }
            if (code != 200) {
                mLoadError = getString(R.string.conversation_error_server, code);
                return entries;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

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
        } catch (java.net.ConnectException e) {
            mLoadError = getString(R.string.conversation_error_connect);
        } catch (Exception e) {
            mLoadError = getString(R.string.conversation_error_generic, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }

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
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, dp(4), 0, dp(4));

        TextView title = new TextView(requireContext());
        title.setText(entry.name);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(14);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.hermes_text_primary));
        item.addView(title);

        LinearLayout details = new LinearLayout(requireContext());
        details.setOrientation(LinearLayout.HORIZONTAL);

        TextView time = new TextView(requireContext());
        time.setText(formatTime(entry.timestamp));
        time.setTextSize(12);
        time.setTextColor(ContextCompat.getColor(requireContext(), R.color.hermes_text_secondary));
        details.addView(time);

        TextView sep = new TextView(requireContext());
        sep.setText(" · ");
        sep.setTextSize(12);
        details.addView(sep);

        TextView platform = new TextView(requireContext());
        platform.setText(entry.platform);
        platform.setTextSize(12);
        platform.setTextColor(ContextCompat.getColor(requireContext(), R.color.hermes_text_secondary));
        details.addView(platform);

        if (entry.size > 0) {
            TextView sep2 = new TextView(requireContext());
            sep2.setText(" · ");
            sep2.setTextSize(12);
            details.addView(sep2);

            TextView msgCount = new TextView(requireContext());
            msgCount.setText(entry.size + " msgs");
            msgCount.setTextSize(12);
            msgCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.hermes_text_secondary));
            details.addView(msgCount);
        }

        item.addView(details);

        View divider = new View(requireContext());
        divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.hermes_divider));
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
        return DateFormat.getTimeFormat(requireContext()).format(new Date(millis));
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
