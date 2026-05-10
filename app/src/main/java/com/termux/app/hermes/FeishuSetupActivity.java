package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.termux.R;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FeishuSetupActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "feishu_setup";
    private static final String KEY_STEP = "current_step";

    private static final int STEP_WELCOME = 0;
    private static final int STEP_DOMAIN = 1;
    private static final int STEP_CREATE_APP = 2;
    private static final int STEP_CREDENTIALS = 3;
    private static final int STEP_TEST = 4;
    private static final int STEP_COMPLETE = 5;
    private static final int TOTAL_STEPS = 6;

    private int mCurrentStep = STEP_WELCOME;
    private String mDomain = "feishu";
    private String mConnectionMode = "websocket";
    private String mAppId = "";
    private String mAppSecret = "";

    private LinearLayout mStepContainer;
    private View mNavButtons;
    private HermesConfigManager mConfigManager;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feishu_setup);

        setSupportActionBar(findViewById(R.id.feishu_toolbar));
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.feishu_setup_title);
        }

        mConfigManager = HermesConfigManager.getInstance();
        mStepContainer = findViewById(R.id.feishu_step_container);
        mNavButtons = findViewById(R.id.feishu_nav_buttons);

        findViewById(R.id.btn_feishu_next).setOnClickListener(v -> nextStep());
        findViewById(R.id.btn_feishu_back).setOnClickListener(v -> prevStep());
        findViewById(R.id.btn_feishu_cancel).setOnClickListener(v -> finish());

        showStep(STEP_WELCOME);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (mCurrentStep > STEP_WELCOME) {
                prevStep();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void showStep(int step) {
        mCurrentStep = step;
        mStepContainer.removeAllViews();
        View stepView = createStepView(step);
        mStepContainer.addView(stepView);

        findViewById(R.id.btn_feishu_back).setVisibility(
                step > STEP_WELCOME ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.btn_feishu_back).setEnabled(step > STEP_WELCOME);

        TextView nextBtn = findViewById(R.id.btn_feishu_next);
        if (step == STEP_COMPLETE) {
            nextBtn.setText(R.string.feishu_finish);
        } else if (step == STEP_TEST) {
            nextBtn.setText(R.string.feishu_skip_test);
        } else {
            nextBtn.setText(R.string.feishu_next);
        }

        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setSubtitle(getString(R.string.feishu_step_progress, step + 1, TOTAL_STEPS));
        }
    }

    private View createStepView(int step) {
        switch (step) {
            case STEP_WELCOME: return createWelcomeStep();
            case STEP_DOMAIN: return createDomainStep();
            case STEP_CREATE_APP: return createCreateAppStep();
            case STEP_CREDENTIALS: return createCredentialsStep();
            case STEP_TEST: return createTestStep();
            case STEP_COMPLETE: return createCompleteStep();
            default: return createWelcomeStep();
        }
    }

    // =========================================================================
    // Step 1: Welcome
    // =========================================================================

    private View createWelcomeStep() {
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        TextView iconTv = new TextView(this);
        iconTv.setText("🤖");
        iconTv.setTextSize(48);
        iconTv.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.addView(iconTv);

        addSpacer(ll, dp(16));

        TextView titleTv = new TextView(this);
        titleTv.setText(R.string.feishu_welcome_title);
        titleTv.setTextSize(20);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.addView(titleTv);

        addSpacer(ll, dp(16));

        TextView descTv = new TextView(this);
        descTv.setText(R.string.feishu_welcome_desc);
        descTv.setTextSize(15);
        descTv.setLineSpacing(dp(4), 1f);
        ll.addView(descTv);

        addSpacer(ll, dp(20));

        TextView stepsTv = new TextView(this);
        stepsTv.setText(R.string.feishu_welcome_steps);
        stepsTv.setTextSize(14);
        stepsTv.setLineSpacing(dp(4), 1f);
        stepsTv.setTextColor(0xFF666666);
        ll.addView(stepsTv);

        sv.addView(ll);
        return sv;
    }

    // =========================================================================
    // Step 2: Choose Domain (Feishu vs Lark)
    // =========================================================================

    private View createDomainStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        addStepHeader(ll, R.string.feishu_step2_title, R.string.feishu_step2_desc);

        TextView label = new TextView(this);
        label.setText(R.string.feishu_domain_label);
        label.setTextSize(16);
        label.setPadding(0, dp(16), 0, dp(8));
        ll.addView(label);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);

        RadioButton rbFeishu = new RadioButton(this);
        rbFeishu.setId(View.generateViewId());
        rbFeishu.setText(R.string.feishu_domain_feishu);
        rbFeishu.setChecked("feishu".equals(mDomain));
        rbFeishu.setTextSize(16);
        rbFeishu.setPadding(0, dp(12), 0, dp(12));

        TextView feishuHint = new TextView(this);
        feishuHint.setText(R.string.feishu_domain_feishu_hint);
        feishuHint.setTextSize(12);
        feishuHint.setTextColor(0xFF888888);
        feishuHint.setPadding(dp(40), 0, 0, dp(8));

        RadioButton rbLark = new RadioButton(this);
        rbLark.setId(View.generateViewId());
        rbLark.setText(R.string.feishu_domain_lark);
        rbLark.setChecked("lark".equals(mDomain));
        rbLark.setTextSize(16);
        rbLark.setPadding(0, dp(12), 0, dp(12));

        TextView larkHint = new TextView(this);
        larkHint.setText(R.string.feishu_domain_lark_hint);
        larkHint.setTextSize(12);
        larkHint.setTextColor(0xFF888888);
        larkHint.setPadding(dp(40), 0, 0, dp(8));

        rg.addView(rbFeishu);
        rg.addView(feishuHint);
        rg.addView(rbLark);
        rg.addView(larkHint);
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            mDomain = checkedId == rbFeishu.getId() ? "feishu" : "lark";
        });
        ll.addView(rg);

        return wrapInScrollView(ll);
    }

    // =========================================================================
    // Step 3: Create App in Developer Console
    // =========================================================================

    private View createCreateAppStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        addStepHeader(ll, R.string.feishu_step3_title, R.string.feishu_step3_desc);

        // Step-by-step instructions
        String[] steps = getResources().getStringArray(R.array.feishu_create_app_steps);
        for (int i = 0; i < steps.length; i++) {
            LinearLayout stepRow = new LinearLayout(this);
            stepRow.setOrientation(LinearLayout.HORIZONTAL);
            stepRow.setPadding(0, dp(4), 0, dp(4));

            TextView numTv = new TextView(this);
            numTv.setText(String.valueOf(i + 1));
            numTv.setTextSize(14);
            numTv.setTypeface(null, android.graphics.Typeface.BOLD);
            numTv.setTextColor(0xFFFFFFFF);
            numTv.setBackgroundColor(0xFF4A90D9);
            numTv.setGravity(Gravity.CENTER);
            int numSize = dp(28);
            LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(numSize, numSize);
            numParams.setMarginEnd(dp(12));
            numParams.gravity = Gravity.CENTER_VERTICAL;
            numTv.setLayoutParams(numParams);

            TextView stepTv = new TextView(this);
            stepTv.setText(steps[i]);
            stepTv.setTextSize(14);
            stepTv.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textParams.gravity = Gravity.CENTER_VERTICAL;
            stepTv.setLayoutParams(textParams);

            stepRow.addView(numTv);
            stepRow.addView(stepTv);
            ll.addView(stepRow);

            addSpacer(ll, dp(4));
        }

        addSpacer(ll, dp(16));

        // Open Console button
        com.google.android.material.button.MaterialButton openConsoleBtn =
                new com.google.android.material.button.MaterialButton(this);
        openConsoleBtn.setText(R.string.feishu_open_console);
        openConsoleBtn.setAllCaps(false);
        openConsoleBtn.setOnClickListener(v -> {
            String url = "feishu".equals(mDomain)
                    ? "https://open.feishu.cn/app"
                    : "https://open.larksuite.com/app";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = Gravity.CENTER_HORIZONTAL;
        openConsoleBtn.setLayoutParams(btnParams);
        ll.addView(openConsoleBtn);

        addSpacer(ll, dp(12));

        TextView tipTv = new TextView(this);
        tipTv.setText(R.string.feishu_create_app_tip);
        tipTv.setTextSize(12);
        tipTv.setTextColor(0xFF888888);
        tipTv.setLineSpacing(dp(2), 1f);
        ll.addView(tipTv);

        return wrapInScrollView(ll);
    }

    // =========================================================================
    // Step 4: Enter Credentials (with QR scan option)
    // =========================================================================

    private View createCredentialsStep() {
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        addStepHeader(ll, R.string.feishu_step4_title, R.string.feishu_step4_desc);

        // QR code section - generate a QR that opens the Feishu credentials page
        TextView qrLabel = new TextView(this);
        qrLabel.setText(R.string.feishu_qr_scan_hint);
        qrLabel.setTextSize(13);
        qrLabel.setTextColor(0xFF666666);
        qrLabel.setPadding(0, 0, 0, dp(8));
        ll.addView(qrLabel);

        ImageView qrImageView = new ImageView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(160), dp(160));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.bottomMargin = dp(16);
        qrImageView.setLayoutParams(qrParams);
        ll.addView(qrImageView);

        // Generate QR code for the credentials page URL
        String credUrl = "feishu".equals(mDomain)
                ? "https://open.feishu.cn/app"
                : "https://open.larksuite.com/app";
        Bitmap qrBitmap = generateQRCode(credUrl, dp(160));
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
        }

        // Auto-detect clipboard for App ID
        String clipboardContent = getClipboardText();
        if (clipboardContent != null && clipboardContent.startsWith("cli_")) {
            addSpacer(ll, dp(4));
            com.google.android.material.button.MaterialButton pasteBtn =
                    new com.google.android.material.button.MaterialButton(this);
            pasteBtn.setText(getString(R.string.feishu_paste_detected, clipboardContent.substring(0, Math.min(16, clipboardContent.length())) + "..."));
            pasteBtn.setAllCaps(false);
            pasteBtn.setCornerRadius(dp(20));
            pasteBtn.setBackgroundColor(0xFF4CAF50);
            pasteBtn.setTextColor(0xFFFFFFFF);
            pasteBtn.setOnClickListener(v -> {
                // Will be filled in after appIdEdit is created
            });
            ll.addView(pasteBtn);
        }

        // Or enter manually section
        addDivider(ll);
        addSpacer(ll, dp(8));

        TextView manualLabel = new TextView(this);
        manualLabel.setText(R.string.feishu_manual_entry_label);
        manualLabel.setTextSize(14);
        manualLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        manualLabel.setPadding(0, 0, 0, dp(8));
        ll.addView(manualLabel);

        // App ID with paste button
        TextView appIdLabel = new TextView(this);
        appIdLabel.setText(R.string.feishu_app_id_label);
        appIdLabel.setPadding(0, dp(12), 0, dp(4));
        ll.addView(appIdLabel);

        LinearLayout appIdRow = new LinearLayout(this);
        appIdRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText appIdEdit = new EditText(this);
        appIdEdit.setId(R.id.feishu_app_id_input);
        appIdEdit.setHint("cli_xxxxxxxxxxxx");
        appIdEdit.setSingleLine();
        appIdEdit.setText(mConfigManager.getFeishuAppId());
        LinearLayout.LayoutParams appIdParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        appIdEdit.setLayoutParams(appIdParams);
        appIdRow.addView(appIdEdit);

        com.google.android.material.button.MaterialButton pasteAppIdBtn =
                new com.google.android.material.button.MaterialButton(this);
        pasteAppIdBtn.setText(R.string.feishu_paste);
        pasteAppIdBtn.setAllCaps(false);
        pasteAppIdBtn.setCornerRadius(dp(16));
        int btnPad = dp(8);
        pasteAppIdBtn.setPadding(btnPad, 0, btnPad, 0);
        pasteAppIdBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (clip != null && !clip.isEmpty()) {
                appIdEdit.setText(clip.trim());
            }
        });
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pasteParams.gravity = Gravity.CENTER_VERTICAL;
        pasteParams.setMarginStart(dp(4));
        pasteAppIdBtn.setLayoutParams(pasteParams);
        appIdRow.addView(pasteAppIdBtn);
        ll.addView(appIdRow);

        // App Secret with paste button
        TextView appSecretLabel = new TextView(this);
        appSecretLabel.setText(R.string.feishu_app_secret_label);
        appSecretLabel.setPadding(0, dp(12), 0, dp(4));
        ll.addView(appSecretLabel);

        LinearLayout appSecretRow = new LinearLayout(this);
        appSecretRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText appSecretEdit = new EditText(this);
        appSecretEdit.setId(R.id.feishu_app_secret_input);
        appSecretEdit.setHint("xxxxxxxxxxxxxxxxxxxxxxxxxx");
        appSecretEdit.setSingleLine();
        appSecretEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        appSecretEdit.setText(mConfigManager.getFeishuAppSecret());
        LinearLayout.LayoutParams secretParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        appSecretEdit.setLayoutParams(secretParams);
        appSecretRow.addView(appSecretEdit);

        com.google.android.material.button.MaterialButton pasteSecretBtn =
                new com.google.android.material.button.MaterialButton(this);
        pasteSecretBtn.setText(R.string.feishu_paste);
        pasteSecretBtn.setAllCaps(false);
        pasteSecretBtn.setCornerRadius(dp(16));
        pasteSecretBtn.setPadding(btnPad, 0, btnPad, 0);
        pasteSecretBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (clip != null && !clip.isEmpty()) {
                appSecretEdit.setText(clip.trim());
            }
        });
        pasteSecretBtn.setLayoutParams(pasteParams);
        appSecretRow.addView(pasteSecretBtn);

        // Toggle visibility button for secret
        com.google.android.material.button.MaterialButton toggleVisBtn =
                new com.google.android.material.button.MaterialButton(this);
        toggleVisBtn.setText(R.string.feishu_toggle_visibility);
        toggleVisBtn.setAllCaps(false);
        toggleVisBtn.setCornerRadius(dp(16));
        toggleVisBtn.setPadding(btnPad, 0, btnPad, 0);
        toggleVisBtn.setOnClickListener(v -> {
            int currentInputType = appSecretEdit.getInputType();
            if ((currentInputType & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
                appSecretEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                appSecretEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            appSecretEdit.setSelection(appSecretEdit.getText().length());
        });
        toggleVisBtn.setLayoutParams(pasteParams);
        appSecretRow.addView(toggleVisBtn);
        ll.addView(appSecretRow);

        // Connection Mode
        TextView connLabel = new TextView(this);
        connLabel.setText(R.string.feishu_connection_mode);
        connLabel.setPadding(0, dp(16), 0, dp(4));
        ll.addView(connLabel);

        RadioGroup connRg = new RadioGroup(this);
        connRg.setOrientation(RadioGroup.VERTICAL);

        RadioButton rbWs = new RadioButton(this);
        rbWs.setId(View.generateViewId());
        rbWs.setText(R.string.feishu_mode_websocket);
        rbWs.setChecked("websocket".equals(mConnectionMode));

        RadioButton rbWh = new RadioButton(this);
        rbWh.setId(View.generateViewId());
        rbWh.setText(R.string.feishu_mode_webhook);
        rbWh.setChecked("webhook".equals(mConnectionMode));

        connRg.addView(rbWs);
        connRg.addView(rbWh);
        connRg.setOnCheckedChangeListener((g, id) -> {
            mConnectionMode = (id == rbWs.getId()) ? "websocket" : "webhook";
        });
        ll.addView(connRg);

        // Validation feedback
        TextView validationTv = new TextView(this);
        validationTv.setId(R.id.feishu_validation_text);
        validationTv.setTextSize(12);
        validationTv.setPadding(0, dp(8), 0, 0);
        validationTv.setVisibility(View.GONE);
        ll.addView(validationTv);

        // Live validation
        TextWatcher validationWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String aid = appIdEdit.getText().toString().trim();
                String sec = appSecretEdit.getText().toString().trim();
                if (validationTv != null) {
                    if (aid.isEmpty() && sec.isEmpty()) {
                        validationTv.setVisibility(View.GONE);
                    } else if (!aid.startsWith("cli_") && !aid.isEmpty()) {
                        validationTv.setText(R.string.feishu_app_id_format_hint);
                        validationTv.setTextColor(0xFFFF9800);
                        validationTv.setVisibility(View.VISIBLE);
                    } else if (aid.length() < 10 || sec.length() < 10) {
                        validationTv.setText(R.string.feishu_credentials_short);
                        validationTv.setTextColor(0xFFFF9800);
                        validationTv.setVisibility(View.VISIBLE);
                    } else {
                        validationTv.setText(R.string.feishu_credentials_ok);
                        validationTv.setTextColor(0xFF4CAF50);
                        validationTv.setVisibility(View.VISIBLE);
                    }
                }
            }
        };
        appIdEdit.addTextChangedListener(validationWatcher);
        appSecretEdit.addTextChangedListener(validationWatcher);

        sv.addView(ll);
        return sv;
    }

    // =========================================================================
    // Step 5: Test Connection
    // =========================================================================

    private View createTestStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        addStepHeader(ll, R.string.feishu_step5_title, R.string.feishu_step5_desc);

        // Config summary
        TextView summaryTv = new TextView(this);
        summaryTv.setId(R.id.feishu_test_summary);
        String domainDisplay = "feishu".equals(mDomain) ? "Feishu (飞书)" : "Lark";
        summaryTv.setText(getString(R.string.feishu_test_config_summary,
                domainDisplay,
                mAppId.substring(0, Math.min(12, mAppId.length())) + "...",
                mConnectionMode));
        summaryTv.setTextSize(14);
        summaryTv.setLineSpacing(dp(4), 1f);
        summaryTv.setPadding(0, 0, 0, dp(16));
        ll.addView(summaryTv);

        // Test status area
        LinearLayout testResultArea = new LinearLayout(this);
        testResultArea.setOrientation(LinearLayout.VERTICAL);
        testResultArea.setId(R.id.feishu_test_result_area);
        testResultArea.setPadding(dp(16), dp(16), dp(16), dp(16));
        testResultArea.setBackgroundColor(0xFFF5F5F5);
        ll.addView(testResultArea);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setId(R.id.feishu_test_progress);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressBar.setLayoutParams(progressParams);
        testResultArea.addView(progressBar);

        TextView testStatusTv = new TextView(this);
        testStatusTv.setId(R.id.feishu_test_status);
        testStatusTv.setText(R.string.feishu_test_ready);
        testStatusTv.setTextSize(14);
        testStatusTv.setGravity(Gravity.CENTER_HORIZONTAL);
        testResultArea.addView(testStatusTv);

        addSpacer(ll, dp(16));

        // Test button
        com.google.android.material.button.MaterialButton testBtn =
                new com.google.android.material.button.MaterialButton(this);
        testBtn.setText(R.string.feishu_run_test);
        testBtn.setAllCaps(false);
        testBtn.setId(R.id.btn_feishu_test);
        testBtn.setOnClickListener(v -> runConnectionTest());
        LinearLayout.LayoutParams testBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        testBtn.setLayoutParams(testBtnParams);
        ll.addView(testBtn);

        return wrapInScrollView(ll);
    }

    // =========================================================================
    // Step 6: Complete
    // =========================================================================

    private View createCompleteStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        // Save config
        mConfigManager.setFeishuConfig(mAppId, mAppSecret, mDomain, mConnectionMode, null, null);

        // Also set in .env for hermes-agent compatibility
        mConfigManager.setEnvVar("FEISHU_APP_ID", mAppId);
        mConfigManager.setEnvVar("FEISHU_APP_SECRET", mAppSecret);
        mConfigManager.setEnvVar("FEISHU_DOMAIN", mDomain);
        mConfigManager.setEnvVar("FEISHU_CONNECTION_MODE", mConnectionMode);

        // Restart gateway if running to pick up new config
        HermesGatewayService.restartIfRunning(this);

        TextView checkTv = new TextView(this);
        checkTv.setText("✅");
        checkTv.setTextSize(48);
        checkTv.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.addView(checkTv);

        addSpacer(ll, dp(16));

        TextView successTv = new TextView(this);
        successTv.setText(R.string.feishu_save_success);
        successTv.setTextSize(20);
        successTv.setTypeface(null, android.graphics.Typeface.BOLD);
        successTv.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.addView(successTv);

        addSpacer(ll, dp(16));

        String domainDisplay = "feishu".equals(mDomain) ? "Feishu (飞书)" : "Lark";
        TextView summary = new TextView(this);
        summary.setText(getString(R.string.feishu_complete_summary,
                domainDisplay,
                mAppId.substring(0, Math.min(8, mAppId.length())) + "...",
                mConnectionMode));
        summary.setTextSize(14);
        summary.setLineSpacing(dp(4), 1f);
        summary.setPadding(dp(16), dp(16), dp(16), dp(16));
        summary.setBackgroundColor(0xFFF5F5F5);
        ll.addView(summary);

        addSpacer(ll, dp(16));

        TextView nextStepsTv = new TextView(this);
        nextStepsTv.setText(R.string.feishu_complete_next_steps);
        nextStepsTv.setTextSize(14);
        nextStepsTv.setLineSpacing(dp(4), 1f);
        ll.addView(nextStepsTv);

        return wrapInScrollView(ll);
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void nextStep() {
        if (mCurrentStep == STEP_COMPLETE) {
            finish();
            return;
        }
        if (mCurrentStep == STEP_CREDENTIALS) {
            if (!captureCredentials()) return;
        }
        showStep(mCurrentStep + 1);
    }

    private void prevStep() {
        if (mCurrentStep > STEP_WELCOME) {
            showStep(mCurrentStep - 1);
        }
    }

    private boolean captureCredentials() {
        EditText appIdEdit = findViewById(R.id.feishu_app_id_input);
        EditText appSecretEdit = findViewById(R.id.feishu_app_secret_input);
        if (appIdEdit != null) mAppId = appIdEdit.getText().toString().trim();
        if (appSecretEdit != null) mAppSecret = appSecretEdit.getText().toString().trim();

        if (mAppId.isEmpty() || mAppSecret.isEmpty()) {
            Toast.makeText(this, R.string.feishu_credentials_required, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    // =========================================================================
    // Connection Test
    // =========================================================================

    private void runConnectionTest() {
        ProgressBar progressBar = findViewById(R.id.feishu_test_progress);
        TextView testStatus = findViewById(R.id.feishu_test_status);
        com.google.android.material.button.MaterialButton testBtn = findViewById(R.id.btn_feishu_test);

        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (testBtn != null) testBtn.setEnabled(false);
        if (testStatus != null) testStatus.setText(R.string.feishu_test_running);

        mExecutor.execute(() -> {
            boolean success = performTest();
            runOnUiThread(() -> {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (testBtn != null) testBtn.setEnabled(true);
                if (testStatus != null) {
                    if (success) {
                        testStatus.setText(R.string.feishu_test_success);
                        testStatus.setTextColor(0xFF4CAF50);
                    } else {
                        testStatus.setText(R.string.feishu_test_warning);
                        testStatus.setTextColor(0xFFFF9800);
                    }
                }
            });
        });
    }

    private boolean performTest() {
        try {
            ProcessBuilder pb = new ProcessBuilder("hermes", "gateway", "check",
                    "--feishu-app-id", mAppId,
                    "--feishu-domain", mDomain);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            // Gateway check not available, just validate format
            return mAppId.startsWith("cli_") && mAppSecret.length() >= 16;
        }
    }

    // =========================================================================
    // QR Code Generation
    // =========================================================================

    private Bitmap generateQRCode(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            return null;
        }
    }

    // =========================================================================
    // UI Helpers
    // =========================================================================

    private void addStepHeader(LinearLayout parent, int titleRes, int descRes) {
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        parent.addView(title);

        if (descRes != 0) {
            TextView desc = new TextView(this);
            desc.setText(descRes);
            desc.setTextSize(14);
            desc.setTextColor(0xFF666666);
            desc.setLineSpacing(dp(3), 1f);
            desc.setPadding(0, 0, 0, dp(16));
            parent.addView(desc);
        }
    }

    private void addSpacer(LinearLayout parent, int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        parent.addView(spacer);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(0, dp(8), 0, dp(8));
        divider.setLayoutParams(params);
        divider.setBackgroundColor(0xFFE0E0E0);
        parent.addView(divider);
    }

    private ScrollView wrapInScrollView(View content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        return sv;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) return text.toString();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
