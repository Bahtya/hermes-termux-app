package com.termux.app.hermes.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.hermes.HermesGatewayService;
import com.termux.app.hermes.HermesWebActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class DashboardFragment extends Fragment {

    private static final String LOG_TAG = "DashboardFragment";

    private static final int[] WEB_PORTS = {9119, 8080, 3000, 5000, 8000, 8888};
    private static final int DEFAULT_PORT = 9119;
    private static final String LOCALHOST = "127.0.0.1";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private String mSessionToken;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mProgressBar = view.findViewById(R.id.progress_bar);
        mWebView = view.findViewById(R.id.web_view);

        setupWebView();
        detectAndLoadHermesWeb();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        if (mWebView == null) return;

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
                if (mProgressBar == null) return;
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
            int maxAttempts = HermesGatewayService.isRunning() ? 6 : 1;

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (getActivity() == null) return;

                for (int port : WEB_PORTS) {
                    if (checkPort(port)) {
                        foundUrl = "http://" + LOCALHOST + ":" + port;
                        HermesWebActivity.sDetectedPort = String.valueOf(port);
                        break;
                    }
                }
                if (foundUrl != null) break;

                if (attempt < maxAttempts - 1) {
                    if (getActivity() != null) {
                        int next = attempt + 2;
                        int total = maxAttempts;
                        requireActivity().runOnUiThread(() -> {
                            if (mWebView != null) {
                                mWebView.loadData("<html><body style='background:#1A1A2E;color:#4AF626;"
                                    + "font-family:monospace;text-align:center;padding-top:40%'>"
                                    + "Scanning for web UI... (" + next + "/" + total + ")</body></html>",
                                    "text/html", "UTF-8");
                            }
                        });
                    }
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                }
            }

            if (foundUrl == null) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (getContext() == null) return;
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.hermes_web_not_found_title)
                            .setMessage(R.string.hermes_web_not_found_message)
                            .setPositiveButton(R.string.hermes_web_start_gateway, (d, w) -> {
                                startGatewayAndReload();
                            })
                            .setNegativeButton(android.R.string.cancel, (d, w) -> {
                                // User cancelled — do nothing
                            })
                            .show();
                });
            } else {
                String token = fetchSessionToken(foundUrl);
                if (token != null) {
                    mSessionToken = token;
                    HermesWebActivity.sSessionToken = token;
                }
                String url = foundUrl;
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (mWebView != null) mWebView.loadUrl(url);
                });
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
            if (getContext() == null) return;
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hermes_not_installed_title)
                    .setMessage(getString(R.string.hermes_not_installed_message, hermesPath))
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        // Dismissed
                    })
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
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (getContext() == null) return;
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.error_dialog_title)
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, (d, w) -> {
                                // Dismissed
                            })
                            .show();
                });
            }
        }).start();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mWebView != null) mWebView.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
    }

    @Override
    public void onDestroyView() {
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroyView();
    }
}
