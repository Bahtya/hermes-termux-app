package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DiscordSetupActivity extends AppCompatActivity {

    private static final int STEP_INSTRUCTIONS = 0;
    private static final int STEP_INTENTS = 1;
    private static final int STEP_CREDENTIALS = 2;
    private static final int STEP_TEST = 3;
    private static final int STEP_COMPLETE = 4;

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
            actionBar.setTitle(R.string.discord_setup_title);
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

        int totalSteps = 5;
        mStepIndicator.setText(getString(R.string.feishu_step_progress, step + 1, totalSteps));

        switch (step) {
            case STEP_INSTRUCTIONS: showInstructions(); break;
            case STEP_INTENTS: showIntents(); break;
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
        title.setText(R.string.discord_welcome_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(R.string.discord_welcome_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(8));
        mContent.addView(desc, wrap);

        TextView steps = new TextView(this);
        steps.setText(R.string.discord_get_token_steps);
        steps.setTextSize(14);
        steps.setPadding(pad, dp(8), pad, pad);
        steps.setBackground(getResources().getDrawable(android.R.drawable.dialog_holo_light_frame));
        mContent.addView(steps, wrap);
    }

    private void showIntents() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.discord_intents_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(R.string.discord_intents_desc);
        desc.setTextSize(14);
        desc.setPadding(pad, 0, pad, dp(12));
        mContent.addView(desc, wrap);

        String[] intentItems = {
                getString(R.string.discord_intent_message_content),
                getString(R.string.discord_intent_server_members),
                getString(R.string.discord_intent_presence)
        };
        for (String item : intentItems) {
            TextView intentLine = new TextView(this);
            intentLine.setText("• " + item);
            intentLine.setTextSize(14);
            intentLine.setPadding(dp(32), dp(4), pad, dp(4));
            mContent.addView(intentLine, wrap);
        }

        // Open developer portal button
        TextView portalBtn = new TextView(this);
        portalBtn.setText(R.string.discord_open_developer_portal);
        portalBtn.setTextColor(0xFF1565C0);
        portalBtn.setTextSize(14);
        portalBtn.setPadding(pad, dp(16), pad, dp(8));
        portalBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://discord.com/developers/applications")));
            } catch (Exception ignored) {}
        });
        mContent.addView(portalBtn, wrap);

        // Invite section
        TextView inviteTitle = new TextView(this);
        inviteTitle.setText(R.string.discord_invite_title);
        inviteTitle.setTextSize(18);
        inviteTitle.setPadding(pad, dp(16), pad, dp(4));
        mContent.addView(inviteTitle, wrap);

        TextView inviteDesc = new TextView(this);
        inviteDesc.setText(R.string.discord_invite_desc);
        inviteDesc.setTextSize(13);
        inviteDesc.setPadding(pad, 0, pad, dp(8));
        mContent.addView(inviteDesc, wrap);

        // Client ID input for generating invite link
        TextView clientIdLabel = new TextView(this);
        clientIdLabel.setText("Application ID (Client ID)");
        clientIdLabel.setTextSize(14);
        clientIdLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        clientIdLabel.setPadding(pad, dp(8), pad, dp(4));
        mContent.addView(clientIdLabel, wrap);

        EditText clientIdInput = new EditText(this);
        clientIdInput.setId(R.id.im_acct_input);
        clientIdInput.setHint("Enter your Discord Application ID");
        clientIdInput.setSingleLine(true);
        String existingClientId = mConfigManager.getEnvVar("DISCORD_CLIENT_ID");
        if (!existingClientId.isEmpty()) clientIdInput.setText(existingClientId);
        mContent.addView(clientIdInput, wrap);

        addPasteButton(clientIdInput, wrap, pad);

        // Copy invite link button
        TextView inviteBtn = new TextView(this);
        inviteBtn.setText(R.string.discord_invite_copy);
        inviteBtn.setTextColor(0xFF1565C0);
        inviteBtn.setTextSize(14);
        inviteBtn.setPadding(pad, dp(8), pad, dp(8));
        inviteBtn.setOnClickListener(v -> {
            String clientId = clientIdInput.getText().toString().trim();
            String inviteLink;
            if (!clientId.isEmpty()) {
                inviteLink = "https://discord.com/api/oauth2/authorize?client_id=" + clientId
                        + "&permissions=274877975552&scope=bot";
                mConfigManager.setEnvVar("DISCORD_CLIENT_ID", clientId);
            } else {
                inviteLink = getString(R.string.discord_invite_link);
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Discord Invite", inviteLink));
            Toast.makeText(this, R.string.feishu_copied, Toast.LENGTH_SHORT).show();
        });
        mContent.addView(inviteBtn, wrap);
    }

    private void showCredentials() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.discord_token_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        EditText tokenInput = new EditText(this);
        tokenInput.setId(R.id.im_token_input);
        tokenInput.setHint(R.string.discord_token_hint);
        tokenInput.setSingleLine(false);
        tokenInput.setPadding(pad, dp(8), pad, dp(8));
        String existingToken = mConfigManager.getEnvVar("DISCORD_BOT_TOKEN");
        if (!existingToken.isEmpty()) tokenInput.setText(existingToken);
        mContent.addView(tokenInput, wrap);

        addPasteButton(tokenInput, wrap, pad);

        TextView usersTitle = new TextView(this);
        usersTitle.setText(R.string.discord_allowed_users_title);
        usersTitle.setTextSize(18);
        usersTitle.setPadding(pad, dp(16), pad, dp(8));
        mContent.addView(usersTitle, wrap);

        EditText usersInput = new EditText(this);
        usersInput.setId(R.id.im_users_input);
        usersInput.setHint(R.string.discord_allowed_users_hint);
        usersInput.setSingleLine(true);
        usersInput.setPadding(pad, dp(8), pad, pad);
        String existingUsers = mConfigManager.getEnvVar("DISCORD_ALLOWED_USERS");
        if (!existingUsers.isEmpty()) usersInput.setText(existingUsers);
        mContent.addView(usersInput, wrap);
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
        EditText tokenInput = findViewById(R.id.im_token_input);
        EditText usersInput = findViewById(R.id.im_users_input);
        if (tokenInput != null) {
            String token = tokenInput.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.im_test_no_token, Toast.LENGTH_SHORT).show();
                mCurrentStep = STEP_CREDENTIALS;
                showStep(STEP_CREDENTIALS);
                return;
            }
            mConfigManager.setEnvVar("DISCORD_BOT_TOKEN", token);
            if (usersInput != null) {
                mConfigManager.setEnvVar("DISCORD_ALLOWED_USERS", usersInput.getText().toString().trim());
            }
            HermesConfigManager.restartGatewayIfRunning(this);
        }

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.discord_test_title);
        title.setTextSize(18);
        title.setPadding(pad, pad, pad, dp(8));
        mContent.addView(title, wrap);

        TextView desc = new TextView(this);
        desc.setText(R.string.discord_test_desc);
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
            status.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
            detail.setVisibility(View.GONE);
            retryBtn.setVisibility(View.GONE);
            runDiscordTest(status, detail, retryBtn);
        });
        mContent.addView(retryBtn, wrap);

        runDiscordTest(status, detail, retryBtn);
    }

    private void runDiscordTest(TextView status, TextView detail, View retryBtn) {
        String token = mConfigManager.getEnvVar("DISCORD_BOT_TOKEN");

        new Thread(() -> {
            String[] result = testDiscordToken(token);
            boolean success = "200".equals(result[0]);
            runOnUiThread(() -> {
                if (success) {
                    status.setText(R.string.discord_test_success);
                    status.setTextColor(ContextCompat.getColor(this, R.color.hermes_status_running));
                    if (detail != null) {
                        detail.setText(getString(R.string.im_test_detail_success, result[1]));
                        detail.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
                        detail.setVisibility(View.VISIBLE);
                    }
                } else {
                    status.setText(R.string.discord_test_fail);
                    status.setTextColor(ContextCompat.getColor(this, R.color.hermes_status_stopped));
                    if (detail != null) {
                        detail.setText(getString(R.string.im_test_detail_fail, result[0], result[1]));
                        detail.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
                        detail.setVisibility(View.VISIBLE);
                    }
                    if (retryBtn != null) {
                        retryBtn.setVisibility(View.VISIBLE);
                    }
                }
            });
        }).start();
    }

    private String[] testDiscordToken(String token) {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String curlPath = binPath + "/curl";
            String url = "https://discord.com/api/v10/users/@me";

            ProcessBuilder pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                    "--connect-timeout", "10",
                    "-H", "Authorization: Bot " + token,
                    url);
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
            if (httpCode == 200 && body.contains("\"username\":\"")) {
                int start = body.indexOf("\"username\":\"") + 12;
                int end = body.indexOf("\"", start);
                if (end > start) {
                    return new String[]{"200", body.substring(start, end)};
                }
            }
            return new String[]{String.valueOf(httpCode),
                    httpCode == 200 ? "OK" : truncate(body, 200)};
        } catch (Exception e) {
            return new String[]{"0", e.getMessage() != null ? e.getMessage() : "Network error"};
        }
    }

    private void showComplete() {
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int pad = dp(16);

        TextView title = new TextView(this);
        title.setText(R.string.discord_complete_title);
        title.setTextSize(20);
        title.setPadding(pad, pad, pad, dp(16));
        mContent.addView(title, wrap);

        String masked = maskToken(mConfigManager.getEnvVar("DISCORD_BOT_TOKEN"));
        TextView summary = new TextView(this);
        summary.setText("Bot Token: " + masked);
        summary.setTextSize(14);
        summary.setPadding(pad, 0, pad, dp(16));
        mContent.addView(summary, wrap);

        TextView nextSteps = new TextView(this);
        nextSteps.setText(R.string.discord_complete_next_steps);
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

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
