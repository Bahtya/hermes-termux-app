package com.termux.app.hermes;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

public class HermesDiagnosticActivity extends AppCompatActivity {

    private LinearLayout mResultsLayout;
    private ScrollView mScrollView;
    private ProgressBar mProgressBar;
    private Button mRunButton;
    private Button mCopyButton;
    private final List<String> mReport = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        setSupportActionBar(new androidx.appcompat.widget.Toolbar(this));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.diagnostic_title);
        }

        mProgressBar = new ProgressBar(this);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(android.view.View.GONE);
        root.addView(mProgressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mScrollView = new ScrollView(this);
        mResultsLayout = new LinearLayout(this);
        mResultsLayout.setOrientation(LinearLayout.VERTICAL);
        mScrollView.addView(mResultsLayout);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(mScrollView, scrollParams);

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(0, dp(8), 0, 0);

        mRunButton = new Button(this);
        mRunButton.setText(R.string.diagnostic_run);
        mRunButton.setOnClickListener(v -> runDiagnostic());
        buttonBar.addView(mRunButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mCopyButton = new Button(this);
        mCopyButton.setText(R.string.diagnostic_copy);
        mCopyButton.setEnabled(false);
        mCopyButton.setOnClickListener(v -> copyReport());
        buttonBar.addView(mCopyButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void runDiagnostic() {
        mRunButton.setEnabled(false);
        mCopyButton.setEnabled(false);
        mProgressBar.setVisibility(android.view.View.VISIBLE);
        mResultsLayout.removeAllViews();
        mReport.clear();

        new Thread(() -> {
            HermesConfigManager config = HermesConfigManager.getInstance();
            String provider = config.getModelProvider();
            String apiKey = config.getApiKey(provider);
            String model = config.getModelName();
            String baseUrl = getBaseUrl(provider);

            addResult("=== Hermes Connection Diagnostic ===", 0);
            addResult("Provider: " + provider, 0);
            addResult("Model: " + model, 0);
            addResult("", 0);

            // Step 1: DNS resolution
            boolean dnsOk = testDns(baseUrl);

            // Step 2: TCP/TLS connection
            boolean tcpOk = dnsOk && testConnection(baseUrl);

            // Step 3: API authentication
            boolean authOk = tcpOk && testApiAuth(provider, apiKey, baseUrl);

            // Step 4: Model inference
            if (authOk) {
                testModelInference(provider, apiKey, model, baseUrl);
            }

            // Step 5: IM connectivity
            testImConnectivity(config);

            addResult("", 0);
            addResult("=== Diagnostic Complete ===", 0);

            runOnUiThread(() -> {
                mProgressBar.setVisibility(android.view.View.GONE);
                mRunButton.setEnabled(true);
                mCopyButton.setEnabled(true);
            });
        }).start();
    }

    private boolean testDns(String baseUrl) {
        addResult("Step 1: DNS Resolution", 0);
        try {
            URL url = new URL(baseUrl);
            String host = url.getHost();
            long start = System.currentTimeMillis();
            InetAddress[] addresses = InetAddress.getAllByName(host);
            long elapsed = System.currentTimeMillis() - start;
            StringBuilder ips = new StringBuilder();
            for (InetAddress addr : addresses) {
                if (ips.length() > 0) ips.append(", ");
                ips.append(addr.getHostAddress());
            }
            addResult("  ✓ Resolved " + host + " → " + ips + " (" + elapsed + "ms)", 1);
            return true;
        } catch (Exception e) {
            addResult("  ✗ DNS resolution failed: " + e.getMessage(), -1);
            return false;
        }
    }

    private boolean testConnection(String baseUrl) {
        addResult("Step 2: TLS Connection", 0);
        try {
            URL url = new URL(baseUrl);
            String host = url.getHost();
            int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
            long start = System.currentTimeMillis();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket)
                    sslContext.getSocketFactory().createSocket(host, port);
            socket.startHandshake();
            socket.close();

            long elapsed = System.currentTimeMillis() - start;
            addResult("  ✓ TLS handshake to " + host + ":" + port + " (" + elapsed + "ms)", 1);
            return true;
        } catch (Exception e) {
            addResult("  ✗ Connection failed: " + e.getMessage(), -1);
            return false;
        }
    }

    private boolean testApiAuth(String provider, String apiKey, String baseUrl) {
        addResult("Step 3: API Authentication", 0);
        if (apiKey == null || apiKey.isEmpty()) {
            if ("ollama".equals(provider)) {
                addResult("  ~ Ollama does not require an API key", 0);
                return true;
            }
            addResult("  ✗ No API key configured", -1);
            return false;
        }
        try {
            URL url = new URL(baseUrl + "/models");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (!"ollama".equals(provider)) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            int code = conn.getResponseCode();
            conn.disconnect();

            if (code == 200 || code == 401) {
                if (code == 200) {
                    addResult("  ✓ API key accepted (HTTP 200)", 1);
                    return true;
                } else {
                    addResult("  ✗ API key rejected (HTTP 401 Unauthorized)", -1);
                    return false;
                }
            } else {
                addResult("  ~ Unexpected response: HTTP " + code, 0);
                return true; // Might still work for inference
            }
        } catch (Exception e) {
            addResult("  ✗ API test failed: " + e.getMessage(), -1);
            return false;
        }
    }

    private void testModelInference(String provider, String apiKey, String model, String baseUrl) {
        addResult("Step 4: Model Inference Test", 0);
        try {
            URL url = new URL(baseUrl + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (!"ollama".equals(provider) && apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            String payload = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":5}";
            conn.getOutputStream().write(payload.getBytes());

            long start = System.currentTimeMillis();
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;

            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                String resp = response.toString();
                if (resp.contains("OK") || resp.contains("choices")) {
                    addResult("  ✓ Model responded successfully (" + elapsed + "ms)", 1);
                } else {
                    addResult("  ~ Model responded but unexpected content (" + elapsed + "ms)", 0);
                }
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) error.append(line);
                reader.close();
                addResult("  ✗ Inference failed: HTTP " + code + " (" + elapsed + "ms)", -1);
                String errMsg = error.toString();
                if (errMsg.length() > 200) errMsg = errMsg.substring(0, 200) + "...";
                addResult("  Error: " + errMsg, -1);
            }
            conn.disconnect();
        } catch (Exception e) {
            addResult("  ✗ Inference test failed: " + e.getMessage(), -1);
        }
    }

    private void testImConnectivity(HermesConfigManager config) {
        addResult("Step 5: IM Connectivity", 0);

        String feishuAppId = config.getFeishuAppId();
        if (feishuAppId != null && !feishuAppId.isEmpty()) {
            addResult("  Feishu: App ID configured (" + feishuAppId.substring(0, Math.min(8, feishuAppId.length())) + "...)", 1);
        } else {
            addResult("  Feishu: Not configured", 0);
        }

        String telegramToken = config.getEnvVar("TELEGRAM_BOT_TOKEN");
        if (!telegramToken.isEmpty()) {
            testTelegramBot(telegramToken);
        } else {
            addResult("  Telegram: Not configured", 0);
        }

        String discordToken = config.getEnvVar("DISCORD_BOT_TOKEN");
        if (!discordToken.isEmpty()) {
            addResult("  Discord: Bot token configured", 1);
        } else {
            addResult("  Discord: Not configured", 0);
        }
    }

    private void testTelegramBot(String token) {
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/getMe");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                if (response.toString().contains("\"ok\":true")) {
                    String username = response.toString();
                    int idx = username.indexOf("\"username\":\"");
                    if (idx >= 0) {
                        int end = username.indexOf("\"", idx + 11);
                        username = username.substring(idx + 11, end);
                        addResult("  ✓ Telegram bot: @" + username, 1);
                    } else {
                        addResult("  ✓ Telegram bot token valid", 1);
                    }
                } else {
                    addResult("  ✗ Telegram bot token invalid", -1);
                }
            } else {
                addResult("  ✗ Telegram API returned HTTP " + code, -1);
            }
            conn.disconnect();
        } catch (Exception e) {
            addResult("  ✗ Telegram test failed: " + e.getMessage(), -1);
        }
    }

    private void addResult(String text, int status) {
        mReport.add(text);
        runOnUiThread(() -> {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(13);
            tv.setPadding(0, dp(2), 0, dp(2));
            if (status == 1) {
                tv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            } else if (status == -1) {
                tv.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            } else {
                tv.setTextColor(Color.GRAY);
            }
            mResultsLayout.addView(tv);
            mScrollView.post(() -> mScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private void copyReport() {
        StringBuilder sb = new StringBuilder();
        for (String line : mReport) sb.append(line).append("\n");
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Diagnostic Report", sb.toString());
        clipboard.setPrimaryClip(clip);
        mCopyButton.setText("Copied!");
        mCopyButton.postDelayed(() -> mCopyButton.setText(R.string.diagnostic_copy), 2000);
    }

    private String getBaseUrl(String provider) {
        switch (provider) {
            case "openai": return "https://api.openai.com/v1";
            case "anthropic": return "https://api.anthropic.com/v1";
            case "google": return "https://generativelanguage.googleapis.com/v1beta";
            case "deepseek": return "https://api.deepseek.com/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "xai": return "https://api.x.ai/v1";
            case "alibaba": return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "mistral": return "https://api.mistral.ai/v1";
            case "nvidia": return "https://integrate.api.nvidia.com/v1";
            case "ollama": return "http://localhost:11434/v1";
            default: return "https://api.openai.com/v1";
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
