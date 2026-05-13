package com.termux.app.hermes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class HermesInstallActivity extends AppCompatActivity {

    private static final String MARKER_FILE =
            TermuxConstants.TERMUX_DATA_HOME_DIR_PATH + "/hermes-installed";
    private static final int MAX_RETRIES = 3;

    private TextView mStatusText;
    private TextView mDetailText;
    private ProgressBar mProgressBar;
    private Button mRetryButton;
    private Button mSkipButton;
    private LinearLayout mStepContainer;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mSuccess = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        androidx.appcompat.widget.Toolbar toolbar = new androidx.appcompat.widget.Toolbar(this);
        toolbar.setTitle(R.string.install_title);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setBackgroundColor(0xFF1A1A2E);
        int toolbarHeight = (int) (56 * getResources().getDisplayMetrics().density);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight));
        root.addView(toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        content.setPadding(pad, pad, pad, pad);

        // Icon
        TextView icon = new TextView(this);
        icon.setText("🚀");
        icon.setTextSize(48);
        icon.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.addView(icon);

        addSpacer(root, dp(16));

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.install_title);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.addView(title);

        addSpacer(root, dp(8));

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.install_subtitle);
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFF666666);
        subtitle.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        subtitle.setLineSpacing(dp(4), 1f);
        content.addView(subtitle);

        addSpacer(root, dp(24));

        // Step indicators
        mStepContainer = new LinearLayout(this);
        mStepContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(mStepContainer);

        addSpacer(root, dp(16));

        // Progress bar
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        content.addView(mProgressBar, pbParams);

        addSpacer(root, dp(12));

        // Status text
        mStatusText = new TextView(this);
        mStatusText.setText(R.string.install_checking);
        mStatusText.setTextSize(14);
        mStatusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.addView(mStatusText);

        addSpacer(root, dp(4));

        // Detail text (for error messages)
        mDetailText = new TextView(this);
        mDetailText.setTextSize(12);
        mDetailText.setTextColor(0xFF999999);
        mDetailText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        mDetailText.setVisibility(View.GONE);
        content.addView(mDetailText);

        addSpacer(root, dp(24));

        // Button area
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        mRetryButton = new Button(this);
        mRetryButton.setText(R.string.install_retry);
        mRetryButton.setVisibility(View.GONE);
        mRetryButton.setOnClickListener(v -> startInstallation());
        buttonRow.addView(mRetryButton);

        mSkipButton = new Button(this);
        mSkipButton.setText(R.string.install_skip);
        mSkipButton.setVisibility(View.VISIBLE);
        mSkipButton.setOnClickListener(v -> proceedToNext());
        buttonRow.addView(mSkipButton);

        content.addView(buttonRow);

        root.addView(content);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        // Check if already installed
        if (new File(MARKER_FILE).exists()) {
            mSuccess = true;
            proceedToNext();
            return;
        }

        startInstallation();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void startInstallation() {
        mRetryButton.setVisibility(View.GONE);
        mDetailText.setVisibility(View.GONE);
        mProgressBar.setProgress(0);

        updateStepIndicators(1);
        mStatusText.setText(R.string.install_checking);

        new Thread(() -> {
            try {
                // Step 1: Check prerequisites
                mHandler.post(() -> {
                    mStatusText.setText(R.string.install_checking);
                    mProgressBar.setProgress(10);
                });
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";

                if (!new File(bashPath).exists()) {
                    throw new RuntimeException("bash not found — bootstrap may still be installing");
                }
                if (!new File(curlPath).exists()) {
                    throw new RuntimeException("curl not found — bootstrap may still be installing");
                }

                Thread.sleep(500);

                // Step 2: Download and install (with mirror fallback)
                mHandler.post(() -> {
                    updateStepIndicators(2);
                    mStatusText.setText(R.string.install_downloading);
                    mProgressBar.setProgress(30);
                });

                HermesInstallHelper.executeInstall(HermesInstallActivity.this, MAX_RETRIES, new HermesInstallHelper.ProgressCallback() {
                    @Override
                    public void onStatus(String message) {
                        mHandler.post(() -> mStatusText.setText(message));
                    }
                    @Override
                    public boolean isCancelled() {
                        return isFinishing() || isDestroyed();
                    }
                }, () -> {
                    HermesInstaller.deployPrerequisitesForActivity(HermesInstallActivity.this);
                });

                // Step 3: Mark installed
                mHandler.post(() -> {
                    updateStepIndicators(3);
                    mStatusText.setText(R.string.install_configuring);
                    mProgressBar.setProgress(80);
                });

                markInstalled();
                HermesConfigManager.reinitialize();

                Thread.sleep(500);

                // Step 3b: Validate installation
                mHandler.post(() -> {
                    mStatusText.setText(R.string.install_validating);
                    mProgressBar.setProgress(90);
                });

                boolean validated = validateInstallation();
                if (!validated) {
                    mHandler.post(() -> mStatusText.setText(R.string.install_validate_fail));
                    Thread.sleep(1500);
                }

                // Step 4: Done
                mHandler.post(() -> {
                    updateStepIndicators(4);
                    mStatusText.setText(R.string.install_complete);
                    mProgressBar.setProgress(100);
                });

                Thread.sleep(800);

                mSuccess = true;
                mHandler.post(this::proceedToNext);

            } catch (Exception e) {
                mHandler.post(() -> {
                    updateStepIndicators(-1);
                    mStatusText.setText(getString(R.string.install_failed, e.getMessage()));
                    mDetailText.setText(R.string.install_failed_help);
                    mDetailText.setVisibility(View.VISIBLE);
                    mRetryButton.setVisibility(View.VISIBLE);
                    mProgressBar.setProgress(0);
                });
            }
        }, "HermesInstaller").start();
    }

    private void updateStepIndicators(int currentStep) {
        mStepContainer.removeAllViews();

        String[] steps = {
                getString(R.string.install_step_check),
                getString(R.string.install_step_download),
                getString(R.string.install_step_configure),
                getString(R.string.install_step_done)
        };

        for (int i = 0; i < steps.length; i++) {
            TextView stepTv = new TextView(this);
            stepTv.setTextSize(14);
            stepTv.setPadding(0, dp(4), 0, dp(4));

            if (currentStep == -1) {
                stepTv.setText(steps[i]);
                stepTv.setTextColor(0xFF999999);
            } else if (i < currentStep - 1) {
                stepTv.setText("✓ " + steps[i]);
                stepTv.setTextColor(0xFF4CAF50);
            } else if (i == currentStep - 1) {
                stepTv.setText("● " + steps[i]);
                stepTv.setTextColor(0xFF2196F3);
            } else {
                stepTv.setText("○ " + steps[i]);
                stepTv.setTextColor(0xFF999999);
            }

            mStepContainer.addView(stepTv);
        }
    }

    private void markInstalled() throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(MARKER_FILE)) {
            out.write("1\n".getBytes("UTF-8"));
        }
    }

    private boolean validateInstallation() {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            // Check marker file
            if (!new File(MARKER_FILE).exists()) return false;
            // Check config directory
            File configDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes");
            if (!configDir.exists() || !configDir.isDirectory()) return false;
            // Try running hermes --version
            ProcessBuilder pb = new ProcessBuilder(binPath + "/hermes", "--version");
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PATH", binPath + ":/system/bin");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String firstLine = reader.readLine();
            p.waitFor();
            reader.close();
            return firstLine != null && !firstLine.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void proceedToNext() {
        if (!HermesSetupWizardActivity.isWizardCompleted(this)) {
            startActivity(new Intent(this, HermesSetupWizardActivity.class));
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void addSpacer(LinearLayout parent, int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        parent.addView(spacer);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
