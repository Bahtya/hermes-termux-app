package com.termux.app.hermes;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;


import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class HermesWebActivity extends AppCompatActivity {

    private static final String LOG_TAG = "HermesWebActivity";

    private static final int[] WEB_PORTS = {9119, 8080, 3000, 5000, 8000, 8888};
    static final int DEFAULT_PORT = 9119;
    private static final String LOCALHOST = "127.0.0.1";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private String mSessionToken;

    static volatile String sDetectedPort;
    static volatile String sSessionToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_web);

        setSupportActionBar(findViewById(R.id.hermes_web_toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.hermes_web_title);
        }

        mProgressBar = findViewById(R.id.hermes_web_progress);
        mWebView = findViewById(R.id.hermes_webview);

        setupWebView();
        detectAndLoadHermesWeb();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && (host.equals(LOCALHOST) || host.equals("localhost"))) {
                    return false;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    mProgressBar.setVisibility(ProgressBar.GONE);
                } else {
                    mProgressBar.setVisibility(ProgressBar.VISIBLE);
                    mProgressBar.setProgress(newProgress);
                }
            }
        });
    }

    private void detectAndLoadHermesWeb() {
        new Thread(() -> {
            String foundUrl = null;

            for (int port : WEB_PORTS) {
                if (checkPort(port)) {
                    foundUrl = "http://" + LOCALHOST + ":" + port;
                    sDetectedPort = String.valueOf(port);
                    break;
                }
            }

            if (foundUrl == null) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.hermes_web_not_found_title)
                            .setMessage(R.string.hermes_web_not_found_message)
                            .setPositiveButton(R.string.hermes_web_start_gateway, (d, w) -> {
                                startGatewayAndReload();
                            })
                            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                            .show();
                });
            } else {
                String token = fetchSessionToken(foundUrl);
                if (token != null) {
                    mSessionToken = token;
                    sSessionToken = token;
                }
                String url = foundUrl;
                runOnUiThread(() -> mWebView.loadUrl(url));
            }
        }).start();
    }

    static String fetchSessionToken(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(baseUrl + "/").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            String html = sb.toString();
            String marker = "window.__HERMES_SESSION_TOKEN__";
            int idx = html.indexOf(marker);
            if (idx < 0) return null;
            int eq = html.indexOf('=', idx + marker.length());
            if (eq < 0) return null;
            int start = html.indexOf('"', eq) + 1;
            int end = html.indexOf('"', start);
            if (start <= 0 || end <= start) return null;
            return html.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean checkPort(int port) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://" + LOCALHOST + ":" + port + "/").openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void startGatewayAndReload() {
        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        String hermesPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";

        if (!new File(hermesPath).exists()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.hermes_not_installed_title)
                    .setMessage(getString(R.string.hermes_not_installed_message, hermesPath))
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .show();
            return;
        }

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(bashPath, "-c",
                        "mkdir -p ~/.hermes/logs && nohup " + hermesPath
                                + " gateway run > ~/.hermes/logs/gateway.log 2>&1 &");
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                        + ":/system/bin:/system/xbin");
                pb.start();

                Thread.sleep(3000);
                detectAndLoadHermesWeb();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.error_dialog_title)
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                            .show();
                });
            }
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            mWebView.destroy();
        }
        super.onDestroy();
    }
}
