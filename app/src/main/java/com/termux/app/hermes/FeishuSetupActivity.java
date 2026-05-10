package com.termux.app.hermes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

public class FeishuSetupActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "feishu_setup";
    private static final String KEY_STEP = "current_step";
    private static final int STEP_WELCOME = 0;
    private static final int STEP_DOMAIN = 1;
    private static final int STEP_CREATE_APP = 2;
    private static final int STEP_CREDENTIALS = 3;
    private static final int STEP_COMPLETE = 4;

    private int mCurrentStep = STEP_WELCOME;
    private String mDomain = "feishu";
    private String mConnectionMode = "websocket";
    private String mAppId = "";
    private String mAppSecret = "";

    private LinearLayout mStepContainer;
    private View mNavButtons;
    private HermesConfigManager mConfigManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feishu_setup);

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
        } else {
            nextBtn.setText(R.string.feishu_next);
        }

        getSupportActionBar().setSubtitle(
                getString(R.string.feishu_step_welcome) + " " + (step + 1) + "/5");
    }

    private View createStepView(int step) {
        switch (step) {
            case STEP_WELCOME: return createWelcomeStep();
            case STEP_DOMAIN: return createDomainStep();
            case STEP_CREATE_APP: return createCreateAppStep();
            case STEP_CREDENTIALS: return createCredentialsStep();
            case STEP_COMPLETE: return createCompleteStep();
            default: return createWelcomeStep();
        }
    }

    private View createWelcomeStep() {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(R.string.feishu_welcome_text);
        tv.setTextSize(16);
        tv.setLineSpacing(8, 1);
        int pad = dpToPx(24);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);
        return sv;
    }

    private View createDomainStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(24);
        ll.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText(R.string.feishu_domain_label);
        label.setTextSize(16);
        label.setPadding(0, 0, 0, dpToPx(16));
        ll.addView(label);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);

        RadioButton rbFeishu = new RadioButton(this);
        rbFeishu.setId(View.generateViewId());
        rbFeishu.setText(R.string.feishu_domain_feishu);
        rbFeishu.setChecked("feishu".equals(mDomain));
        rbFeishu.setTextSize(16);
        rbFeishu.setPadding(0, dpToPx(8), 0, dpToPx(8));

        RadioButton rbLark = new RadioButton(this);
        rbLark.setId(View.generateViewId());
        rbLark.setText(R.string.feishu_domain_lark);
        rbLark.setChecked("lark".equals(mDomain));
        rbLark.setTextSize(16);
        rbLark.setPadding(0, dpToPx(8), 0, dpToPx(8));

        rg.addView(rbFeishu);
        rg.addView(rbLark);
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            mDomain = checkedId == rbFeishu.getId() ? "feishu" : "lark";
        });
        ll.addView(rg);

        return ll;
    }

    private View createCreateAppStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(24);
        ll.setPadding(pad, pad, pad, pad);

        TextView instructions = new TextView(this);
        instructions.setText(R.string.feishu_create_app_text);
        instructions.setTextSize(15);
        instructions.setLineSpacing(6, 1);
        ll.addView(instructions);

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
        ll.addView(openConsoleBtn);

        return ll;
    }

    private View createCredentialsStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(24);
        ll.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.feishu_credentials_title);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, dpToPx(16));
        ll.addView(title);

        // App ID
        TextView appIdLabel = new TextView(this);
        appIdLabel.setText(R.string.feishu_app_id_label);
        appIdLabel.setPadding(0, dpToPx(16), 0, dpToPx(4));
        ll.addView(appIdLabel);

        EditText appIdEdit = new EditText(this);
        appIdEdit.setId(R.id.feishu_app_id_input);
        appIdEdit.setHint("cli_xxxxxxxxxxxx");
        appIdEdit.setSingleLine();
        appIdEdit.setText(mConfigManager.getFeishuAppId());
        ll.addView(appIdEdit);

        // App Secret
        TextView appSecretLabel = new TextView(this);
        appSecretLabel.setText(R.string.feishu_app_secret_label);
        appSecretLabel.setPadding(0, dpToPx(16), 0, dpToPx(4));
        ll.addView(appSecretLabel);

        EditText appSecretEdit = new EditText(this);
        appSecretEdit.setId(R.id.feishu_app_secret_input);
        appSecretEdit.setHint("xxxxxxxxxxxxxxxxxxxxxxxxxx");
        appSecretEdit.setSingleLine();
        appSecretEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        appSecretEdit.setText(mConfigManager.getFeishuAppSecret());
        ll.addView(appSecretEdit);

        // Connection Mode
        TextView connLabel = new TextView(this);
        connLabel.setText(R.string.feishu_connection_mode);
        connLabel.setPadding(0, dpToPx(16), 0, dpToPx(4));
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

        return ll;
    }

    private View createCompleteStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(24);
        ll.setPadding(pad, pad, pad, pad);

        mConfigManager.setFeishuConfig(mAppId, mAppSecret, mDomain, mConnectionMode, null, null);

        TextView successTv = new TextView(this);
        successTv.setText(R.string.feishu_save_success);
        successTv.setTextSize(18);
        successTv.setPadding(0, dpToPx(32), 0, dpToPx(16));
        ll.addView(successTv);

        TextView summary = new TextView(this);
        summary.setText("Domain: " + ("feishu".equals(mDomain) ? "Feishu (飞书)" : "Lark")
                + "\nApp ID: " + mAppId.substring(0, Math.min(8, mAppId.length())) + "..."
                + "\nConnection: " + mConnectionMode);
        summary.setTextSize(14);
        summary.setLineSpacing(6, 1);
        ll.addView(summary);

        return ll;
    }

    private void nextStep() {
        if (mCurrentStep == STEP_COMPLETE) {
            finish();
            return;
        }
        // Capture credentials before leaving step 3
        if (mCurrentStep == STEP_CREDENTIALS) {
            EditText appIdEdit = findViewById(R.id.feishu_app_id_input);
            EditText appSecretEdit = findViewById(R.id.feishu_app_secret_input);
            if (appIdEdit != null) mAppId = appIdEdit.getText().toString().trim();
            if (appSecretEdit != null) mAppSecret = appSecretEdit.getText().toString().trim();

            if (mAppId.isEmpty() || mAppSecret.isEmpty()) {
                Toast.makeText(this, "App ID and App Secret are required", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        showStep(mCurrentStep + 1);
    }

    private void prevStep() {
        if (mCurrentStep > STEP_WELCOME) {
            showStep(mCurrentStep - 1);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
