package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class HermesSetupWizardActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "hermes_setup";
    private static final String KEY_WIZARD_COMPLETED = "wizard_completed";
    private static final String KEY_WIZARD_DISMISSED = "wizard_dismissed";

    private HermesConfigManager mConfigManager;
    private int mCurrentStep = 0;
    private static final int STEP_WELCOME = 0;
    private static final int STEP_LLM = 1;
    private static final int STEP_IM = 2;
    private static final int STEP_START = 3;
    private static final int STEP_DONE = 4;

    private ScrollView mScrollView;
    private LinearLayout mContentContainer;
    private Button mBtnBack;
    private Button mBtnNext;
    private TextView mStepIndicator;

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

        mStepIndicator = new TextView(this);
        mStepIndicator.setTextSize(13);
        mStepIndicator.setTextColor(0xFF666666);
        mStepIndicator.setGravity(android.view.Gravity.CENTER);
        mStepIndicator.setPadding(0, dp(8), 0, dp(4));
        root.addView(mStepIndicator);

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

    @Override
    public void onBackPressed() {
        if (mCurrentStep > STEP_WELCOME) {
            navigateBack();
        } else {
            markDismissed();
            super.onBackPressed();
        }
    }

    private void markDismissed() {
        if (!isWizardCompleted(this)) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_WIZARD_DISMISSED, true)
                    .apply();
        }
    }

    private void showStep(int step) {
        mCurrentStep = step;
        mContentContainer.removeAllViews();
        mStepIndicator.setText(getString(R.string.hermes_setup_step_indicator, step + 1, STEP_DONE + 1));

        mBtnBack.setVisibility(step > STEP_WELCOME ? View.VISIBLE : View.GONE);
        mBtnNext.setText(step < STEP_DONE ? getString(R.string.feishu_next) : getString(R.string.feishu_finish));

        switch (step) {
            case STEP_WELCOME: showWelcomeStep(); break;
            case STEP_LLM: showLlmStep(); break;
            case STEP_IM: showImStep(); break;
            case STEP_START: showStartStep(); break;
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

        // Provider spinner
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

        // Update model list when provider changes
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                updateModelSpinner(providerValues[pos]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // API key input with paste button
        addSpacer(dp(16));
        addLabel(getString(R.string.llm_api_key_title));

        LinearLayout apiKeyRow = new LinearLayout(this);
        apiKeyRow.setOrientation(LinearLayout.HORIZONTAL);
        apiKeyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(R.string.hermes_setup_api_key_hint);
        apiKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String existingKey = mConfigManager.getApiKey(currentProvider);
        if (!existingKey.isEmpty()) apiKeyInput.setText(existingKey);
        apiKeyRow.addView(apiKeyInput);
        tagView(apiKeyInput, "api_key_input");

        Button pasteBtn = new Button(this);
        pasteBtn.setText(R.string.feishu_paste);
        pasteBtn.setTextSize(12);
        pasteBtn.setPadding(dp(8), 0, dp(8), 0);
        pasteBtn.setOnClickListener(v -> {
            String clip = getClipboardText();
            if (clip != null && !clip.isEmpty()) apiKeyInput.setText(clip);
        });
        apiKeyRow.addView(pasteBtn);

        mContentContainer.addView(apiKeyRow);

        // Model spinner (dynamic)
        addSpacer(dp(16));
        addLabel(getString(R.string.llm_model_title));
        Spinner modelSpinner = new Spinner(this);
        mContentContainer.addView(modelSpinner);
        tagView(modelSpinner, "model_spinner");
        updateModelSpinner(currentProvider);

        // Test connection button
        addSpacer(dp(16));
        Button testBtn = new Button(this);
        testBtn.setText(R.string.llm_test_connection_title);
        testBtn.setOnClickListener(v -> runWizardLlmTest(testBtn));
        LinearLayout.LayoutParams testBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testBtnParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        testBtn.setLayoutParams(testBtnParams);
        mContentContainer.addView(testBtn);
        tagView(testBtn, "test_btn");

        TextView testStatus = new TextView(this);
        testStatus.setPadding(0, dp(8), 0, 0);
        testStatus.setTextSize(13);
        mContentContainer.addView(testStatus);
        tagView(testStatus, "test_status");
    }

    private void updateModelSpinner(String provider) {
        Spinner modelSpinner = findTaggedView("model_spinner");
        if (modelSpinner == null) return;

        int arrayResId = getModelArrayResId(provider);
        if (arrayResId != 0) {
            String[] models = getResources().getStringArray(arrayResId);
            ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, models);
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            modelSpinner.setAdapter(modelAdapter);

            String currentModel = mConfigManager.getModelName();
            for (int i = 0; i < models.length; i++) {
                if (models[i].equals(currentModel)) {
                    modelSpinner.setSelection(i);
                    break;
                }
            }
        }
    }

    private int getModelArrayResId(String provider) {
        switch (provider) {
            case "openai": return R.array.llm_models_openai;
            case "anthropic": return R.array.llm_models_anthropic;
            case "google": return R.array.llm_models_google;
            case "deepseek": return R.array.llm_models_deepseek;
            case "openrouter": return R.array.llm_models_openrouter;
            case "xai": return R.array.llm_models_xai;
            case "alibaba": return R.array.llm_models_alibaba;
            case "mistral": return R.array.llm_models_mistral;
            case "nvidia": return R.array.llm_models_nvidia;
            case "ollama": return R.array.llm_models_ollama;
            default: return 0;
        }
    }

    private void runWizardLlmTest(Button testBtn) {
        // Save first
        saveLlmConfig();

        String provider = mConfigManager.getModelProvider();
        String apiKey = mConfigManager.getApiKey(provider);
        String model = mConfigManager.getModelName();
        TextView testStatus = findTaggedView("test_status");

        if ("ollama".equals(provider)) {
            apiKey = "ollama";
        } else if (apiKey.isEmpty()) {
            if (testStatus != null) testStatus.setText(R.string.llm_test_no_key);
            return;
        }

        testBtn.setEnabled(false);
        if (testStatus != null) testStatus.setText(R.string.llm_test_running);
        String finalApiKey = apiKey;

        new Thread(() -> {
            String[] result = performProviderTest(provider, finalApiKey);
            runOnUiThread(() -> {
                testBtn.setEnabled(true);
                if (testStatus == null) return;
                if (result[0].equals("success")) {
                    testStatus.setText(getString(R.string.llm_test_success, model));
                    testStatus.setTextColor(0xFF388E3C);
                } else if (result[0].equals("auth")) {
                    testStatus.setText(R.string.llm_test_fail_auth);
                    testStatus.setTextColor(0xFFD32F2F);
                } else if (result[0].equals("network")) {
                    testStatus.setText(R.string.llm_test_fail_network);
                    testStatus.setTextColor(0xFFD32F2F);
                } else {
                    testStatus.setText(getString(R.string.llm_test_fail_generic, result[1]));
                    testStatus.setTextColor(0xFFD32F2F);
                }
            });
        }).start();
    }

    private String[] performProviderTest(String provider, String apiKey) {
        try {
            String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
            String curlPath = binPath + "/curl";
            if (!new File(curlPath).exists()) return new String[]{"generic", "curl not available"};

            String url = getProviderUrl(provider);
            if (url == null) return new String[]{"generic", "Unknown provider"};

            ProcessBuilder pb;
            if ("ollama".equals(provider)) {
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                        "--connect-timeout", "5", url);
            } else if ("google".equals(provider)) {
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                        "--connect-timeout", "10", url + apiKey);
            } else {
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}",
                        "--connect-timeout", "10", "-H", "Authorization: Bearer " + apiKey, url);
            }
            pb.environment().put("PATH", binPath + ":/system/bin");
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String output = reader.readLine();
            p.waitFor();

            int httpCode = 0;
            try { httpCode = Integer.parseInt(output != null ? output.trim() : "0"); } catch (NumberFormatException ignored) {}

            if (httpCode == 200) return new String[]{"success", ""};
            if (httpCode == 401 || httpCode == 403) return new String[]{"auth", "" + httpCode};
            if (httpCode == 0) return new String[]{"network", "no response"};
            return new String[]{"generic", "HTTP " + httpCode};
        } catch (Exception e) {
            return new String[]{"network", e.getMessage()};
        }
    }

    private String getProviderUrl(String provider) {
        String baseUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
        switch (provider) {
            case "openai":     return "https://api.openai.com/v1/models";
            case "anthropic":  return "https://api.anthropic.com/v1/models";
            case "google":     return "https://generativelanguage.googleapis.com/v1beta/models?key=";
            case "deepseek":   return "https://api.deepseek.com/models";
            case "openrouter": return "https://openrouter.ai/api/v1/models";
            case "xai":        return "https://api.x.ai/v1/models";
            case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1/models";
            case "mistral":    return "https://api.mistral.ai/v1/models";
            case "nvidia":     return "https://integrate.api.nvidia.com/v1/models";
            case "ollama":     return baseUrl.isEmpty() ? "http://localhost:11434/api/tags" : baseUrl + "/api/tags";
            case "custom":     return baseUrl.isEmpty() ? null : baseUrl + "/models";
            default:           return null;
        }
    }

    private void showImStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_feishu);
        }

        addTitle(R.string.hermes_setup_im_title);
        addParagraph(R.string.hermes_setup_im_text);

        // Feishu button
        addSpacer(dp(8));
        Button feishuBtn = new Button(this);
        feishuBtn.setText(R.string.hermes_setup_open_feishu_wizard);
        feishuBtn.setOnClickListener(v -> startActivity(new Intent(this, FeishuSetupActivity.class)));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        feishuBtn.setLayoutParams(btnParams);
        mContentContainer.addView(feishuBtn);

        // Telegram button
        Button telegramBtn = new Button(this);
        telegramBtn.setText(R.string.hermes_telegram_setup_title);
        telegramBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_TELEGRAM);
            startActivity(intent);
        });
        telegramBtn.setLayoutParams(btnParams);
        mContentContainer.addView(telegramBtn);

        // Discord button
        Button discordBtn = new Button(this);
        discordBtn.setText(R.string.hermes_discord_setup_title);
        discordBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_DISCORD);
            startActivity(intent);
        });
        discordBtn.setLayoutParams(btnParams);
        mContentContainer.addView(discordBtn);

        // WhatsApp button
        Button whatsappBtn = new Button(this);
        whatsappBtn.setText(R.string.hermes_setup_open_whatsapp_wizard);
        whatsappBtn.setOnClickListener(v -> startActivity(new Intent(this, WhatsAppSetupActivity.class)));
        whatsappBtn.setLayoutParams(btnParams);
        mContentContainer.addView(whatsappBtn);

        addSpacer(dp(16));
        TextView skipNote = new TextView(this);
        skipNote.setText(R.string.hermes_setup_feishu_skip);
        skipNote.setTextColor(0xFF888888);
        skipNote.setTextSize(13);
        mContentContainer.addView(skipNote);
    }

    private void showStartStep() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(R.string.hermes_setup_step_start);
        }

        addTitle(R.string.hermes_setup_start_title);
        addParagraph(R.string.hermes_setup_start_text);

        // Start gateway button
        addSpacer(dp(8));
        Button startBtn = new Button(this);
        startBtn.setText(R.string.gateway_start_title);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        startBtn.setLayoutParams(btnParams);
        mContentContainer.addView(startBtn);
        tagView(startBtn, "start_btn");

        // Status text
        TextView statusText = new TextView(this);
        statusText.setPadding(0, dp(16), 0, 0);
        statusText.setTextSize(15);
        statusText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        mContentContainer.addView(statusText);
        tagView(statusText, "start_status");

        // Check if already running
        if (HermesGatewayService.isRunning()) {
            startBtn.setEnabled(false);
            statusText.setText(R.string.hermes_setup_start_already_running);
            statusText.setTextColor(0xFF388E3C);
        } else {
            startBtn.setOnClickListener(v -> {
                startBtn.setEnabled(false);
                statusText.setText(R.string.gateway_started);
                statusText.setTextColor(0xFF1565C0);

                Intent startIntent = new Intent(this, HermesGatewayService.class);
                startIntent.setAction(HermesGatewayService.ACTION_START);
                startService(startIntent);

                // Update status after a short delay
                statusText.postDelayed(() -> {
                    if (HermesGatewayService.isRunning()) {
                        statusText.setText(R.string.hermes_setup_start_success);
                        statusText.setTextColor(0xFF388E3C);
                    } else {
                        statusText.setText(R.string.hermes_setup_start_failed);
                        statusText.setTextColor(0xFFD32F2F);
                        startBtn.setEnabled(true);
                    }
                }, 3000);
            });
        }

        addSpacer(dp(16));
        TextView skipNote = new TextView(this);
        skipNote.setText(R.string.hermes_setup_start_skip);
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

        String provider = mConfigManager.getModelProvider();
        String apiKey = mConfigManager.getApiKey(provider);
        boolean hasLLM = apiKey != null && !apiKey.isEmpty();
        boolean hasFeishu = mConfigManager.isFeishuConfigured();
        boolean hasTelegram = !mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty();
        boolean hasDiscord = !mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty();
        boolean hasWhatsApp = !mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty();

        StringBuilder summary = new StringBuilder();
        if (hasLLM) {
            summary.append(getString(R.string.hermes_setup_summary_ai, provider + " / " + mConfigManager.getModelName()));
        } else {
            summary.append(getString(R.string.hermes_setup_summary_ai_not_configured));
        }
        summary.append("\n");
        summary.append(formatPlatformStatus("Feishu", hasFeishu)).append("\n");
        summary.append(formatPlatformStatus("Telegram", hasTelegram)).append("\n");
        summary.append(formatPlatformStatus("Discord", hasDiscord)).append("\n");
        summary.append(formatPlatformStatus("WhatsApp", hasWhatsApp)).append("\n");
        summary.append(HermesGatewayService.isRunning()
                ? getString(R.string.hermes_setup_summary_gateway_running)
                : getString(R.string.hermes_setup_summary_gateway_not_started));

        TextView summaryView = new TextView(this);
        summaryView.setText(summary.toString());
        summaryView.setTextSize(16);
        summaryView.setPadding(0, dp(16), 0, 0);
        mContentContainer.addView(summaryView);

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WIZARD_COMPLETED, true)
                .apply();
    }

    private void navigateNext() {
        if (mCurrentStep == STEP_LLM) {
            saveLlmConfig();
            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            if (apiKey.isEmpty() && !"ollama".equals(provider)) {
                Toast.makeText(this, getString(R.string.llm_test_no_key), Toast.LENGTH_SHORT).show();
                return;
            }
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
        Spinner modelSpinner = findTaggedView("model_spinner");

        String[] providerValues = getResources().getStringArray(R.array.llm_provider_values);
        if (providerSpinner != null) {
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

        if (modelSpinner != null) {
            Object selected = modelSpinner.getSelectedItem();
            if (selected != null) {
                String model = selected.toString();
                if (!model.isEmpty()) {
                    mConfigManager.setModelName(model);
                }
            }
        }
    }

    public static boolean isWizardCompleted(android.content.Context context) {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_WIZARD_COMPLETED, false);
    }

    public static boolean isWizardDismissed(android.content.Context context) {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_WIZARD_DISMISSED, false);
    }

    public static void clearDismissedFlag(android.content.Context context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_WIZARD_DISMISSED)
                .apply();
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                if (item != null && item.getText() != null) return item.getText().toString();
            }
        } catch (Exception ignored) {}
        return null;
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

    private void addTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
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

    private void addParagraph(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
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

    private String formatPlatformStatus(String name, boolean configured) {
        return configured
                ? getString(R.string.hermes_setup_summary_platform_configured, name)
                : getString(R.string.hermes_setup_summary_platform_not_configured, name);
    }
}
