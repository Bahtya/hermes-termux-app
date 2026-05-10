package com.termux.app.hermes;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;

public class HermesSetupWizardActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "hermes_setup";
    private static final String KEY_WIZARD_COMPLETED = "wizard_completed";

    private HermesConfigManager mConfigManager;
    private int mCurrentStep = 0;
    private static final int STEP_WELCOME = 0;
    private static final int STEP_LLM = 1;
    private static final int STEP_FEISHU = 2;
    private static final int STEP_DONE = 3;

    private ScrollView mScrollView;
    private LinearLayout mContentContainer;
    private Button mBtnBack;
    private Button mBtnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mConfigManager = HermesConfigManager.getInstance();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.hermes_setup_wizard_title);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setBackgroundColor(0xFF1A1A2E);
        toolbar.setSubtitleTextColor(0xFFCCCCCC);
        setSupportActionBar(toolbar);

        int toolbarHeight = (int) (56 * getResources().getDisplayMetrics().density);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight));
        root.addView(toolbar);

        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mContentContainer = new LinearLayout(this);
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.setPadding(dp(24), dp(24), dp(24), dp(16));
        mScrollView.addView(mContentContainer);
        root.addView(mScrollView);

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(dp(16), dp(8), dp(16), dp(16));
        buttonBar.setGravity(android.view.Gravity.END);

        mBtnBack = new Button(this);
        mBtnBack.setText(R.string.feishu_back);
        mBtnBack.setVisibility(View.GONE);
        mBtnBack.setOnClickListener(v -> navigateBack());
        buttonBar.addView(mBtnBack);

        LinearLayout.LayoutParams backParams = (LinearLayout.LayoutParams) mBtnBack.getLayoutParams();
        backParams.setMarginEnd(dp(8));
        mBtnBack.setLayoutParams(backParams);

        mBtnNext = new Button(this);
        mBtnNext.setText(R.string.feishu_next);
        mBtnNext.setOnClickListener(v -> navigateNext());
        buttonBar.addView(mBtnNext);

        root.addView(buttonBar);
        setContentView(root);

        showStep(STEP_WELCOME);
    }

    private void showStep(int step) {
        mCurrentStep = step;
        mContentContainer.removeAllViews();

        mBtnBack.setVisibility(step > STEP_WELCOME ? View.VISIBLE : View.GONE);
        mBtnNext.setText(step < STEP_DONE ? getString(R.string.feishu_next) : getString(R.string.feishu_finish));

        switch (step) {
            case STEP_WELCOME: showWelcomeStep(); break;
            case STEP_LLM: showLlmStep(); break;
            case STEP_FEISHU: showFeishuStep(); break;
            case STEP_DONE: showDoneStep(); break;
        }

        mScrollView.post(() -> mScrollView.fullScroll(View.FOCUS_UP));
    }

    private void showWelcomeStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_welcome);
        }

        addTitle(R.string.hermes_setup_welcome_title);
        addParagraph(R.string.hermes_setup_welcome_text);
    }

    private void showLlmStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_llm);
        }

        addTitle(R.string.hermes_setup_llm_title);
        addParagraph(R.string.hermes_setup_llm_text);

        addLabel(getString(R.string.llm_provider_title));
        Spinner providerSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.llm_provider_names, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(adapter);
        mContentContainer.addView(providerSpinner);
        tagView(providerSpinner, "provider_spinner");

        String currentProvider = mConfigManager.getModelProvider();
        String[] providerValues = getResources().getStringArray(R.array.llm_provider_values);
        for (int i = 0; i < providerValues.length; i++) {
            if (providerValues[i].equals(currentProvider)) {
                providerSpinner.setSelection(i);
                break;
            }
        }

        addSpacer(dp(16));
        addLabel(getString(R.string.llm_api_key_title));
        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(R.string.hermes_setup_api_key_hint);
        apiKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setSingleLine(true);
        mContentContainer.addView(apiKeyInput);
        tagView(apiKeyInput, "api_key_input");

        addSpacer(dp(16));
        addLabel(getString(R.string.llm_model_title));
        EditText modelInput = new EditText(this);
        String currentModel = mConfigManager.getModelName();
        if (currentModel != null && !currentModel.isEmpty()) {
            modelInput.setText(currentModel);
        } else {
            modelInput.setText("gpt-4o");
        }
        modelInput.setSingleLine(true);
        mContentContainer.addView(modelInput);
        tagView(modelInput, "model_input");
    }

    private void showFeishuStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_feishu);
        }

        addTitle(R.string.hermes_setup_feishu_title);
        addParagraph(R.string.hermes_setup_feishu_text);

        Button setupFeishuBtn = new Button(this);
        setupFeishuBtn.setText(R.string.hermes_setup_open_feishu_wizard);
        setupFeishuBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, FeishuSetupActivity.class));
        });
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        setupFeishuBtn.setLayoutParams(btnParams);
        mContentContainer.addView(setupFeishuBtn);

        addSpacer(dp(16));
        TextView skipNote = new TextView(this);
        skipNote.setText(R.string.hermes_setup_feishu_skip);
        skipNote.setTextColor(0xFF888888);
        skipNote.setTextSize(13);
        mContentContainer.addView(skipNote);
    }

    private void showDoneStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_done);
        }

        addTitle(R.string.hermes_setup_done_title);
        addParagraph(R.string.hermes_setup_done_text);

        // Show summary
        String provider = mConfigManager.getModelProvider();
        String apiKey = mConfigManager.getApiKey(provider);
        boolean hasLLM = apiKey != null && !apiKey.isEmpty();
        boolean hasFeishu = mConfigManager.isFeishuConfigured();

        StringBuilder summary = new StringBuilder();
        summary.append("LLM: ").append(hasLLM ? "Configured (" + provider + ")" : "Not configured");
        summary.append("\nFeishu: ").append(hasFeishu ? "Configured" : "Not configured");

        TextView summaryView = new TextView(this);
        summaryView.setText(summary.toString());
        summaryView.setTextSize(16);
        summaryView.setPadding(0, dp(16), 0, 0);
        mContentContainer.addView(summaryView);

        // Mark wizard as completed
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WIZARD_COMPLETED, true)
                .apply();
    }

    private void navigateNext() {
        if (mCurrentStep == STEP_LLM) {
            saveLlmConfig();
        }
        if (mCurrentStep == STEP_DONE) {
            finish();
            return;
        }
        showStep(mCurrentStep + 1);
    }

    private void navigateBack() {
        if (mCurrentStep > STEP_WELCOME) {
            showStep(mCurrentStep - 1);
        }
    }

    private void saveLlmConfig() {
        Spinner providerSpinner = findTaggedView("provider_spinner");
        EditText apiKeyInput = findTaggedView("api_key_input");
        EditText modelInput = findTaggedView("model_input");

        if (providerSpinner != null) {
            String[] providerValues = getResources().getStringArray(R.array.llm_provider_values);
            int pos = providerSpinner.getSelectedItemPosition();
            if (pos >= 0 && pos < providerValues.length) {
                mConfigManager.setModelProvider(providerValues[pos]);
            }
        }

        if (apiKeyInput != null) {
            String key = apiKeyInput.getText().toString().trim();
            if (!key.isEmpty()) {
                mConfigManager.setApiKey(mConfigManager.getModelProvider(), key);
            }
        }

        if (modelInput != null) {
            String model = modelInput.getText().toString().trim();
            if (!model.isEmpty()) {
                mConfigManager.setModelName(model);
            }
        }
    }

    public static boolean isWizardCompleted(android.content.Context context) {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_WIZARD_COMPLETED, false);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T findTaggedView(String tag) {
        for (int i = 0; i < mContentContainer.getChildCount(); i++) {
            View child = mContentContainer.getChildAt(i);
            if (tag.equals(child.getTag())) return (T) child;
        }
        return null;
    }

    private void tagView(View view, String tag) {
        view.setTag(tag);
    }

    private void addTitle(int textResId) {
        TextView tv = new TextView(this);
        tv.setText(textResId);
        tv.setTextSize(22);
        tv.setTextColor(0xFF1A1A2E);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(16));
        mContentContainer.addView(tv);
    }

    private void addParagraph(int textResId) {
        TextView tv = new TextView(this);
        tv.setText(textResId);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(4), 1f);
        tv.setPadding(0, 0, 0, dp(16));
        mContentContainer.addView(tv);
    }

    private void addLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(0xFF666666);
        tv.setPadding(0, 0, 0, dp(4));
        mContentContainer.addView(tv);
    }

    private void addSpacer(int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        mContentContainer.addView(spacer);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
