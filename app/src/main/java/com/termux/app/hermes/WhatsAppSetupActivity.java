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

import androidx.core.content.ContextCompat;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class WhatsAppSetupActivity extends AppCompatActivity {

    private static final int STEP_INSTRUCTIONS = 0;
    private static final int STEP_CREDENTIALS = 1;
    private static final int STEP_TEST = 2;
    private static final int STEP_COMPLETE = 3;

    private int mCurrentStep = STEP_INSTRUCTIONS;
    private HermesConfigManager mConfigManager;

    private LinearLayout mContent;
    private View mBtnBack;
    private View mBtnNext;
    private TextView mStepIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_im_setup);

        setSupportActionBar(findViewById(R.id.im_setup_toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.whatsapp_setup_title);
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

    private void showStep(int step) {
        mCurrentStep = step;
        mContent.removeAllViews();

        int totalSteps = 4;
        mStepIndicator.setText(getString(R.string.feishu_step_progress, step + 1, totalSteps));

        switch (step) {
            case STEP_INSTRUCTIONS: showInstructions(); break;
            case STEP_CREDENTIALS: showCredentials(); break;
            case STEP_TEST: showTest(); break;
            case STEP_COMPLETE: showComplete(); break;
        }

        mBtnBack.setVisibility(step > STEP_INSTRUCTIONS ? View.VISIBLE : View.GONE);
        mBtnNext.setVisibility(step < STEP_COMPLETE ? View.VISIBLE : View.GONE);
        ((TextView) mBtnNext).setText(step == STEP_COMPLETE ? R.string.feishu_finish : R.string.feishu_next);
    }

    private void showInstructions() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.whatsapp_welcome_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(R.string.whatsapp_setup_intro_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(8));
        mContent.addView(desc, wrap);

        TextView steps = new TextView(this);
        steps.setText(R.string.whatsapp_get_token_steps);
        steps.setTextSize(14);
        steps.setPadding(pad, dp(8), pad, pad);
        steps.setBackground(getResources().getDrawable(android.R.drawable.dialog_holo_light_frame));
        mContent.addView(steps, wrap);
    }

    private void showCredentials() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.whatsapp_credentials_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        // Phone Number ID
        TextView phoneLabel = new TextView(this);
        phoneLabel.setText(R.string.whatsapp_phone_id_title);
        phoneLabel.setTextSize(14);
        phoneLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        mContent.addView(phoneLabel, wrap);
        EditText phoneInput = new EditText(this);
        phoneInput.setId(R.id.im_token_input);
        phoneInput.setHint(R.string.whatsapp_phone_id_hint);
        phoneInput.setSingleLine(true);
        String existingPhone = mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID");
        if (!existingPhone.isEmpty()) phoneInput.setText(existingPhone);
        mContent.addView(phoneInput, wrap);

        // Paste button
        addPasteButton(phoneInput, wrap, pad);

        // Access Token
        TextView tokenLabel = new TextView(this);
        tokenLabel.setText(R.string.whatsapp_token_title);
        tokenLabel.setTextSize(14);
        tokenLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tokenLabel.setPadding(0, dp(8), 0, 0);
        mContent.addView(tokenLabel, wrap);
        EditText tokenInput = new EditText(this);
        tokenInput.setId(R.id.im_users_input);
        tokenInput.setHint(R.string.whatsapp_token_hint);
        tokenInput.setSingleLine(false);
        String existingToken = mConfigManager.getEnvVar("WHATSAPP_ACCESS_TOKEN");
        if (!existingToken.isEmpty()) tokenInput.setText(existingToken);
        mContent.addView(tokenInput, wrap);

        addPasteButton(tokenInput, wrap, pad);

        // Business Account ID
        TextView acctLabel = new TextView(this);
        acctLabel.setText(R.string.whatsapp_account_id_title);
        acctLabel.setTextSize(14);
        acctLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        acctLabel.setPadding(0, dp(8), 0, 0);
        mContent.addView(acctLabel, wrap);
        EditText acctInput = new EditText(this);
        acctInput.setId(R.id.im_acct_input);
        acctInput.setHint(R.string.whatsapp_account_id_hint);
        acctInput.setSingleLine(true);
        String existingAcct = mConfigManager.getEnvVar("WHATSAPP_BUSINESS_ACCOUNT_ID");
        if (!existingAcct.isEmpty()) acctInput.setText(existingAcct);
        mContent.addView(acctInput, wrap);

        // Webhook Verify Token
        TextView verifyLabel = new TextView(this);
        verifyLabel.setText(R.string.whatsapp_verify_token_title);
        verifyLabel.setTextSize(14);
        verifyLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        verifyLabel.setPadding(0, dp(8), 0, 0);
        mContent.addView(verifyLabel, wrap);
        EditText verifyInput = new EditText(this);
        verifyInput.setId(R.id.im_verify_input);
        verifyInput.setHint(R.string.whatsapp_verify_token_hint);
        verifyInput.setSingleLine(true);
        String existingVerify = mConfigManager.getEnvVar("WHATSAPP_VERIFY_TOKEN");
        if (!existingVerify.isEmpty()) verifyInput.setText(existingVerify);
        mContent.addView(verifyInput, wrap);
    }

    private void addPasteButton(EditText target, LinearLayout.LayoutParams wrap, int pad) {
        View pasteBtn = new TextView(this);
        ((TextView) pasteBtn).setText(R.string.feishu_paste);
        pasteBtn.setPadding(pad, dp(4), pad, dp(4));
        pasteBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (clip != null && !clip.isEmpty()) {
                target.setText(clip);
            }
        });
        mContent.addView(pasteBtn, wrap);
    }

    private void showTest() {
        // Save credentials before testing
        EditText phoneInput = findViewById(R.id.im_token_input);
        EditText tokenInput = findViewById(R.id.im_users_input);
        EditText acctInput = findViewById(R.id.im_acct_input);
        EditText verifyInput = findViewById(R.id.im_verify_input);
        if (phoneInput != null) {
            String phone = phoneInput.getText().toString().trim();
            if (phone.isEmpty()) {
                Toast.makeText(this, R.string.whatsapp_phone_required, Toast.LENGTH_SHORT).show();
                mCurrentStep = STEP_CREDENTIALS;
                showStep(STEP_CREDENTIALS);
                return;
            }
            mConfigManager.setEnvVar("WHATSAPP_PHONE_NUMBER_ID", phone);
            if (tokenInput != null) {
                mConfigManager.setEnvVar("WHATSAPP_ACCESS_TOKEN", tokenInput.getText().toString().trim());
            }
            if (acctInput != null) {
                mConfigManager.setEnvVar("WHATSAPP_BUSINESS_ACCOUNT_ID", acctInput.getText().toString().trim());
            }
            if (verifyInput != null) {
                mConfigManager.setEnvVar("WHATSAPP_VERIFY_TOKEN", verifyInput.getText().toString().trim());
            }
            HermesConfigManager.restartGatewayIfRunning(this);
        }

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.whatsapp_test_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(R.string.whatsapp_test_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(16));
        mContent.addView(desc, wrap);

        TextView status = new TextView(this);
        status.setId(R.id.im_test_status);
        status.setText(R.string.im_test_running);
        status.setTextSize(14);
        status.setPadding(pad, 0, pad, dp(8));
        mContent.addView(status, wrap);

        runWhatsAppTest(status);
    }

    private void runWhatsAppTest(TextView status) {
        String phoneId = mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID");
        String token = mConfigManager.getEnvVar("WHATSAPP_ACCESS_TOKEN");

        new Thread(() -> {
            boolean success = testWhatsAppCredentials(phoneId, token);
            runOnUiThread(() -> {
                if (success) {
                    status.setText(R.string.whatsapp_test_success);
                    status.setTextColor(ContextCompat.getColor(this, R.color.hermes_status_running));
                } else {
                    status.setText(R.string.whatsapp_test_fail);
                    status.setTextColor(ContextCompat.getColor(this, R.color.hermes_status_stopped));
                }
            });
        }).start();
    }

    private boolean testWhatsAppCredentials(String phoneId, String token) {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String curlPath = binPath + "/curl";
            String url = "https://graph.facebook.com/v21.0/" + phoneId;

            ProcessBuilder pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                    "--connect-timeout", "10",
                    "-H", "Authorization: Bearer " + token,
                    url);
            pb.environment().put("PATH", binPath + ":/system/bin");
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String lastLine = "";
            while ((line = reader.readLine()) != null) {
                lastLine = line;
            }
            p.waitFor();

            int httpCode = 0;
            try {
                httpCode = Integer.parseInt(lastLine.trim());
            } catch (NumberFormatException ignored) {}

            return httpCode == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void showComplete() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.whatsapp_complete_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(16));
        mContent.addView(title, wrap);

        String masked = maskToken(mConfigManager.getEnvVar("WHATSAPP_ACCESS_TOKEN"));
        TextView summary = new TextView(this);
        summary.setText(getString(R.string.whatsapp_complete_summary, masked));
        summary.setTextSize(14);
        summary.setPadding(pad, 0, pad, dp(16));
        mContent.addView(summary, wrap);

        TextView nextSteps = new TextView(this);
        nextSteps.setText(R.string.whatsapp_complete_next_steps);
        nextSteps.setTextSize(14);
        nextSteps.setPadding(pad, 0, pad, pad);
        mContent.addView(nextSteps, wrap);
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
}
