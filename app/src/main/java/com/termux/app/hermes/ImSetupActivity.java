package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ImSetupActivity extends AppCompatActivity {

    public static final String EXTRA_PLATFORM = "platform";
    public static final String PLATFORM_TELEGRAM = "telegram";
    public static final String PLATFORM_DISCORD = "discord";

    private static final int STEP_INSTRUCTIONS = 0;
    private static final int STEP_TOKEN = 1;
    private static final int STEP_TEST = 2;
    private static final int STEP_COMPLETE = 3;
    private static final int REQUEST_CODE_QR_SCAN = 0x494D;

    private String mPlatform;
    private int mCurrentStep = STEP_INSTRUCTIONS;
    private HermesConfigManager mConfigManager;

    private LinearLayout mContent;
    private View mBtnBack;
    private View mBtnNext;
    private TextView mStepIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPlatform = getIntent().getStringExtra(EXTRA_PLATFORM);
        if (mPlatform == null) mPlatform = PLATFORM_TELEGRAM;

        setContentView(R.layout.activity_im_setup);

        setSupportActionBar(findViewById(R.id.im_setup_toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(isTelegram() ? R.string.telegram_setup_title : R.string.discord_setup_title);
        }

        mConfigManager = HermesConfigManager.getInstance();
        mContent = findViewById(R.id.im_setup_content);
        mBtnBack = findViewById(R.id.btn_im_back);
        mBtnNext = findViewById(R.id.btn_im_next);
        mStepIndicator = findViewById(R.id.im_step_indicator);

        mBtnBack.setOnClickListener(v -> goBack());
        mBtnNext.setOnClickListener(v -> goNext());

        showStep(STEP_INSTRUCTIONS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isTelegram() {
        return PLATFORM_TELEGRAM.equals(mPlatform);
    }

    private void showStep(int step) {
        mCurrentStep = step;
        mContent.removeAllViews();

        int totalSteps = 4;
        String[] stepNames = isTelegram()
                ? new String[]{"Instructions", "Bot Token", "Test", "Done"}
                : new String[]{"Instructions", "Bot Token", "Test", "Done"};
        mStepIndicator.setText(getString(R.string.feishu_step_progress, step + 1, totalSteps));

        switch (step) {
            case STEP_INSTRUCTIONS: showInstructions(); break;
            case STEP_TOKEN: showTokenEntry(); break;
            case STEP_TEST: showTest(); break;
            case STEP_COMPLETE: showComplete(); break;
        }

        mBtnBack.setVisibility(step > STEP_INSTRUCTIONS ? View.VISIBLE : View.GONE);
        mBtnNext.setVisibility(step < STEP_COMPLETE ? View.VISIBLE : View.GONE);

        if (step == STEP_COMPLETE) {
            ((TextView) mBtnNext).setText(R.string.feishu_finish);
        } else if (step == STEP_TEST) {
            ((TextView) mBtnNext).setText(R.string.feishu_next);
        } else {
            ((TextView) mBtnNext).setText(R.string.feishu_next);
        }
    }

    private void showInstructions() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(isTelegram() ? R.string.telegram_welcome_title : R.string.discord_welcome_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(isTelegram() ? R.string.telegram_welcome_desc : R.string.discord_welcome_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(8));
        mContent.addView(desc, wrap);

        TextView steps = new TextView(this);
        steps.setText(isTelegram() ? R.string.telegram_get_token_steps : R.string.discord_get_token_steps);
        steps.setTextSize(14);
        steps.setPadding(pad, dp(8), pad, pad);
        steps.setBackground(getResources().getDrawable(android.R.drawable.dialog_holo_light_frame));
        mContent.addView(steps, wrap);
    }

    private void showTokenEntry() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(isTelegram() ? R.string.telegram_token_title : R.string.discord_token_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        EditText tokenInput = new EditText(this);
        tokenInput.setId(R.id.im_token_input);
        tokenInput.setHint(isTelegram() ? R.string.telegram_token_hint : R.string.discord_token_hint);
        tokenInput.setSingleLine(false);
        tokenInput.setPadding(pad, dp(8), pad, dp(8));
        String existingToken = isTelegram()
                ? mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN")
                : mConfigManager.getEnvVar("DISCORD_BOT_TOKEN");
        if (!existingToken.isEmpty()) {
            tokenInput.setText(existingToken);
        }
        mContent.addView(tokenInput, wrap);

        View pasteBtn = new TextView(this);
        ((TextView) pasteBtn).setText(R.string.feishu_paste);
        pasteBtn.setPadding(pad, dp(4), pad, dp(4));
        pasteBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (clip != null && !clip.isEmpty()) {
                tokenInput.setText(clip);
            }
        });
        mContent.addView(pasteBtn, wrap);

        // Scan QR code button
        View scanBtn = new TextView(this);
        ((TextView) scanBtn).setText(R.string.im_scan_qr_code);
        scanBtn.setPadding(pad, dp(4), pad, dp(4));
        scanBtn.setOnClickListener(v -> launchQrScanner());
        mContent.addView(scanBtn, wrap);

        TextView usersTitle = new TextView(this);
        usersTitle.setText(isTelegram() ? R.string.telegram_allowed_users_title : R.string.discord_allowed_users_title);
        usersTitle.setTextSize(18);
        usersTitle.setPadding(pad, dp(16), pad, dp(8));
        mContent.addView(usersTitle, wrap);

        EditText usersInput = new EditText(this);
        usersInput.setId(R.id.im_users_input);
        usersInput.setHint(isTelegram() ? R.string.telegram_allowed_users_hint : R.string.discord_allowed_users_hint);
        usersInput.setSingleLine(true);
        usersInput.setPadding(pad, dp(8), pad, pad);
        String existingUsers = isTelegram()
                ? mConfigManager.getEnvVar("TELEGRAM_ALLOWED_USERS")
                : mConfigManager.getEnvVar("DISCORD_ALLOWED_USERS");
        if (!existingUsers.isEmpty()) {
            usersInput.setText(existingUsers);
        }
        mContent.addView(usersInput, wrap);
    }

    private void showTest() {
        // Save token before testing
        EditText tokenInput = findViewById(R.id.im_token_input);
        EditText usersInput = findViewById(R.id.im_users_input);
        if (tokenInput != null) {
            String token = tokenInput.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.im_test_no_token, Toast.LENGTH_SHORT).show();
                mCurrentStep = STEP_TOKEN;
                showStep(STEP_TOKEN);
                return;
            }
            String tokenKey = isTelegram() ? "TELEGRAM_BOT_TOKEN" : "DISCORD_BOT_TOKEN";
            String usersKey = isTelegram() ? "TELEGRAM_ALLOWED_USERS" : "DISCORD_ALLOWED_USERS";
            mConfigManager.setEnvVar(tokenKey, token);
            if (usersInput != null) {
                mConfigManager.setEnvVar(usersKey, usersInput.getText().toString().trim());
            }
            HermesConfigManager.restartGatewayIfRunning(this);
        }

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(isTelegram() ? R.string.telegram_test_title : R.string.discord_test_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(isTelegram() ? R.string.telegram_test_desc : R.string.discord_test_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(16));
        mContent.addView(desc, wrap);

        TextView status = new TextView(this);
        status.setId(R.id.im_test_status);
        status.setText(R.string.im_test_running);
        status.setTextSize(14);
        status.setPadding(pad, 0, pad, dp(8));
        mContent.addView(status, wrap);

        TextView detail = new TextView(this);
        detail.setId(R.id.im_test_detail);
        detail.setTextSize(12);
        detail.setPadding(pad, 0, pad, dp(8));
        detail.setVisibility(View.GONE);
        mContent.addView(detail, wrap);

        Button retryBtn = new Button(this);
        retryBtn.setId(R.id.im_test_retry);
        retryBtn.setText(R.string.im_test_retry);
        retryBtn.setVisibility(View.GONE);
        retryBtn.setOnClickListener(v -> {
            status.setText(R.string.im_test_running);
            status.setTextColor(getResources().getColor(android.R.color.secondary_text_dark));
            detail.setVisibility(View.GONE);
            retryBtn.setVisibility(View.GONE);
            runImTest(status, detail, retryBtn);
        });
        mContent.addView(retryBtn, wrap);

        runImTest(status, detail, retryBtn);
    }

    private void runImTest(TextView status, TextView detail, View retryBtn) {
        String tokenKey = isTelegram() ? "TELEGRAM_BOT_TOKEN" : "DISCORD_BOT_TOKEN";
        String token = mConfigManager.getEnvVar(tokenKey);

        new Thread(() -> {
            String[] result = testTokenWithDetail(token);
            boolean success = "200".equals(result[0]);
            runOnUiThread(() -> {
                if (success) {
                    status.setText(isTelegram() ? R.string.telegram_test_success : R.string.discord_test_success);
                    status.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    if (detail != null) {
                        detail.setText(getString(R.string.im_test_detail_success, result[1]));
                        detail.setTextColor(getResources().getColor(android.R.color.secondary_text_dark));
                        detail.setVisibility(View.VISIBLE);
                    }
                } else {
                    status.setText(isTelegram() ? R.string.telegram_test_fail : R.string.discord_test_fail);
                    status.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    if (detail != null) {
                        detail.setText(getString(R.string.im_test_detail_fail, result[0], result[1]));
                        detail.setTextColor(getResources().getColor(android.R.color.secondary_text_dark));
                        detail.setVisibility(View.VISIBLE);
                    }
                    if (retryBtn != null) {
                        retryBtn.setVisibility(View.VISIBLE);
                    }
                }
            });
        }).start();
    }

    private String[] testTokenWithDetail(String token) {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String curlPath = binPath + "/curl";
            String url;

            if (isTelegram()) {
                url = "https://api.telegram.org/bot" + token + "/getMe";
            } else {
                url = "https://discord.com/api/v10/users/@me";
            }

            ProcessBuilder pb;
            if (isTelegram()) {
                pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                        "--connect-timeout", "10", url);
            } else {
                pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                        "--connect-timeout", "10", "-H", "Authorization: Bot " + token, url);
            }

            pb.environment().put("PATH", binPath + ":/system/bin");
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            String lastLine = "";
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                lastLine = line;
            }
            p.waitFor();

            int httpCode = 0;
            try {
                httpCode = Integer.parseInt(lastLine.trim());
            } catch (NumberFormatException ignored) {}

            String body = output.toString().replace(lastLine, "").trim();
            if (httpCode == 200 && isTelegram()) {
                // Extract bot username from response
                if (body.contains("\"username\":\"")) {
                    int start = body.indexOf("\"username\":\"") + 12;
                    int end = body.indexOf("\"", start);
                    return new String[]{"200", "@" + body.substring(start, end)};
                }
            }
            return new String[]{String.valueOf(httpCode), httpCode == 200 ? "OK" : truncate(body, 200)};
        } catch (Exception e) {
            return new String[]{"0", e.getMessage() != null ? e.getMessage() : "Network error"};
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private boolean testToken(String token) {
        return "200".equals(testTokenWithDetail(token)[0]);
    }

    private void showComplete() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(isTelegram() ? R.string.telegram_complete_title : R.string.discord_complete_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(16));
        mContent.addView(title, wrap);

        String masked = maskToken(mConfigManager.getEnvVar(
                isTelegram() ? "TELEGRAM_BOT_TOKEN" : "DISCORD_BOT_TOKEN"));
        TextView summary = new TextView(this);
        summary.setText("Bot Token: " + masked);
        summary.setTextSize(14);
        summary.setPadding(pad, 0, pad, dp(16));
        mContent.addView(summary, wrap);

        TextView nextSteps = new TextView(this);
        nextSteps.setText(isTelegram() ? R.string.telegram_complete_next_steps : R.string.discord_complete_next_steps);
        nextSteps.setTextSize(14);
        nextSteps.setPadding(pad, 0, pad, pad);
        mContent.addView(nextSteps, wrap);

        ((TextView) mBtnNext).setText(R.string.feishu_finish);
    }

    private void goNext() {
        if (mCurrentStep < STEP_COMPLETE) {
            showStep(mCurrentStep + 1);
        } else {
            finish();
        }
    }

    private void goBack() {
        if (mCurrentStep > STEP_INSTRUCTIONS) {
            showStep(mCurrentStep - 1);
        }
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                if (item != null && item.getText() != null) {
                    return item.getText().toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 10) return "****";
        return token.substring(0, 5) + "..." + token.substring(token.length() - 5);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void launchQrScanner() {
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
        intent.putExtra("PROMPT_MESSAGE", getString(R.string.im_scan_qr_prompt));

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_CODE_QR_SCAN);
            return;
        }

        String[] scannerPackages = {
                "com.google.zxing.client.android",
                "com.srowen.bs.android",
                "com.gamma.scan",
                "la.droid.qr",
                "com.application_onestop.qrscanner"
        };
        for (String pkg : scannerPackages) {
            intent.setPackage(pkg);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(intent, REQUEST_CODE_QR_SCAN);
                return;
            }
        }

        Toast.makeText(this, R.string.feishu_no_qr_scanner, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_QR_SCAN && resultCode == RESULT_OK && data != null) {
            String scanResult = data.getStringExtra("SCAN_RESULT");
            if (scanResult != null && !scanResult.isEmpty() && mCurrentStep == STEP_TOKEN) {
                EditText tokenInput = findViewById(R.id.im_token_input);
                if (tokenInput != null) {
                    tokenInput.setText(scanResult.trim());
                    Toast.makeText(this, R.string.im_scan_token_filled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
