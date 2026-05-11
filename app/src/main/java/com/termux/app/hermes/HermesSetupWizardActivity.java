package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Setup wizard using an enum state machine.
 * Each {@link WizardState} owns its enter/exit/validate logic, making the
 * flow declarative and impossible to break by forgetting to update a switch.
 *
 * State graph:
 * <pre>
 * WELCOME ──next──> LLM ──next──> IM ──next──> START ──next──> DONE
 *                    ↑ skip        ↑ skip         ↑ skip
 *                Ollama quick-start jumps directly to DONE
 * </pre>
 */
public class HermesSetupWizardActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "hermes_setup";
    private static final String KEY_WIZARD_BASIC_DONE = "wizard_basic_done";
    private static final String KEY_WIZARD_LAST_STATE = "wizard_last_state";
    private static final String KEY_WELCOME_SHOWN = "welcome_shown";

    // ──────────────────────────────────────────────────────────────────────────
    // State machine
    // ──────────────────────────────────────────────────────────────────────────

    enum WizardState {
        WELCOME {
            @Override WizardState next() { return LLM; }
            @Override String prefsKey() { return KEY_WELCOME_SHOWN; }
            @Override void onEnter(HermesSetupWizardActivity ctx) { ctx.showWelcomeStep(); }
            @Override int subtitleResId() { return R.string.hermes_setup_step_welcome; }
        },

        LLM {
            @Override WizardState next() { return IM; }
            @Override boolean canSkip() { return false; }
            @Override String prefsKey() { return "step_llm_done"; }
            @Override int subtitleResId() { return R.string.hermes_setup_step_llm; }

            @Override void onEnter(HermesSetupWizardActivity ctx) { ctx.showLlmStep(); }

            @Override int validate(HermesSetupWizardActivity ctx) {
                ctx.saveLlmConfig();
                String provider = ctx.readCurrentProvider();
                String apiKey = ctx.readCurrentApiKey(provider);
                if (apiKey.isEmpty() && !"ollama".equals(provider)) {
                    return R.string.llm_test_no_key;
                }
                return 0;
            }

            @Override void onExitCompleted(HermesSetupWizardActivity ctx) {
                ctx.saveLlmConfig();
                ctx.markWizardBasicDone();
            }
        },

        IM {
            @Override WizardState next() { return START; }
            @Override boolean canSkip() { return true; }
            @Override String prefsKey() { return "step_im_done"; }
            @Override void onEnter(HermesSetupWizardActivity ctx) { ctx.showImStep(); }
            @Override int subtitleResId() { return R.string.hermes_setup_step_feishu; }
        },

        START {
            @Override WizardState next() { return DONE; }
            @Override boolean canSkip() { return true; }
            @Override String prefsKey() { return "step_start_done"; }
            @Override void onEnter(HermesSetupWizardActivity ctx) { ctx.showStartStep(); }
            @Override int subtitleResId() { return R.string.hermes_setup_step_start; }
        },

        DONE {
            @Override WizardState next() { return null; }
            @Override String prefsKey() { return null; }
            @Override void onEnter(HermesSetupWizardActivity ctx) { ctx.showDoneStep(); }
            @Override int subtitleResId() { return R.string.hermes_setup_step_done; }
        };

        // ── Template methods (defaults) ──

        /** Next state on advance. {@code null} means the wizard is finished. */
        abstract WizardState next();

        /** Whether this step can be skipped. */
        boolean canSkip() { return false; }

        /** SharedPrefs key that marks this step as done. {@code null} = not trackable. */
        abstract String prefsKey();

        /** Render the step UI. */
        abstract void onEnter(HermesSetupWizardActivity ctx);

        /** Toolbar subtitle for this step. */
        abstract int subtitleResId();

        /**
         * Validate before advancing.
         * @return 0 if valid, or a string resource ID for the error toast.
         */
        int validate(HermesSetupWizardActivity ctx) { return 0; }

        /** Called when this step completes (advance or skip). */
        void onExitCompleted(HermesSetupWizardActivity ctx) {}

        /** Previous state for back navigation. {@code null} for the first state. */
        WizardState previous() {
            WizardState[] all = values();
            return ordinal() > 0 ? all[ordinal() - 1] : null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Instance state
    // ──────────────────────────────────────────────────────────────────────────

    private HermesConfigManager mConfigManager;
    private WizardState mCurrentState = WizardState.WELCOME;

    private ScrollView mScrollView;
    private LinearLayout mContentContainer;
    private Button mBtnBack;
    private Button mBtnNext;
    private Button mBtnSkip;
    private TextView mStepIndicator;

    // ──────────────────────────────────────────────────────────────────────────
    // Activity lifecycle
    // ──────────────────────────────────────────────────────────────────────────

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
        mStepIndicator.setGravity(Gravity.CENTER);
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
        buttonBar.setGravity(Gravity.END);

        mBtnBack = new Button(this);
        mBtnBack.setText(R.string.feishu_back);
        mBtnBack.setVisibility(View.GONE);
        mBtnBack.setOnClickListener(v -> navigateBack());
        buttonBar.addView(mBtnBack);
        LinearLayout.LayoutParams backParams = (LinearLayout.LayoutParams) mBtnBack.getLayoutParams();
        backParams.setMarginEnd(dp(8));
        mBtnBack.setLayoutParams(backParams);

        mBtnSkip = new Button(this);
        mBtnSkip.setText(R.string.hermes_setup_skip);
        mBtnSkip.setVisibility(View.GONE);
        mBtnSkip.setOnClickListener(v -> skipCurrentStep());
        buttonBar.addView(mBtnSkip);
        LinearLayout.LayoutParams skipParams = (LinearLayout.LayoutParams) mBtnSkip.getLayoutParams();
        skipParams.setMarginEnd(dp(8));
        mBtnSkip.setLayoutParams(skipParams);

        mBtnNext = new Button(this);
        mBtnNext.setText(R.string.feishu_next);
        mBtnNext.setOnClickListener(v -> navigateNext());
        buttonBar.addView(mBtnNext);

        root.addView(buttonBar);
        setContentView(root);

        // Resume from persisted state
        transitionTo(loadResumedState(), false);
    }

    @Override
    public void onBackPressed() {
        if (mCurrentState.previous() != null) {
            navigateBack();
        } else {
            saveCurrentState();
            super.onBackPressed();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Navigation (no per-step logic — fully delegated to state machine)
    // ──────────────────────────────────────────────────────────────────────────

    private void navigateNext() {
        int err = mCurrentState.validate(this);
        if (err != 0) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
            return;
        }
        mCurrentState.onExitCompleted(this);
        markStateDone(mCurrentState);

        WizardState next = mCurrentState.next();
        if (next == null) {
            // DONE state: finish
            finish();
            return;
        }
        transitionTo(next, true);
    }

    private void navigateBack() {
        saveCurrentState();
        WizardState prev = mCurrentState.previous();
        if (prev != null) {
            transitionTo(prev, false);
        }
    }

    private void skipCurrentStep() {
        markStateDone(mCurrentState);
        WizardState next = mCurrentState.next();
        if (next != null) {
            transitionTo(next, true);
        }
    }

    /** Jump to an arbitrary state (used by Ollama quick-start). */
    private void jumpTo(WizardState target) {
        // Mark intermediate states as done
        for (WizardState s : WizardState.values()) {
            if (s.ordinal() < target.ordinal()) {
                markStateDone(s);
            }
        }
        markWizardBasicDone();
        transitionTo(target, true);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State transitions
    // ──────────────────────────────────────────────────────────────────────────

    private void transitionTo(WizardState target, boolean persist) {
        mCurrentState = target;
        if (persist) saveCurrentState();

        // Clear content
        mContentContainer.removeAllViews();

        // Update toolbar subtitle
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(target.subtitleResId());
        }

        // Update step indicator
        updateStepIndicator();

        // Update buttons
        mBtnBack.setVisibility(target.previous() != null ? View.VISIBLE : View.GONE);
        mBtnSkip.setVisibility(target.canSkip() ? View.VISIBLE : View.GONE);
        mBtnNext.setText(target.next() != null
                ? getString(R.string.feishu_next)
                : getString(R.string.feishu_finish));

        // Render the step
        target.onEnter(this);

        mScrollView.post(() -> mScrollView.fullScroll(View.FOCUS_UP));
    }

    private void updateStepIndicator() {
        WizardState[] all = WizardState.values();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append("  >  ");
            WizardState s = all[i];
            if (s == mCurrentState) {
                sb.append("[").append(i + 1).append("]");
            } else if (isStateDone(s)) {
                sb.append("✓");
            } else {
                sb.append(i + 1);
            }
        }
        mStepIndicator.setText(sb.toString());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Persistence
    // ──────────────────────────────────────────────────────────────────────────

    private void saveCurrentState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_WIZARD_LAST_STATE, mCurrentState.name())
                .apply();
    }

    private WizardState loadResumedState() {
        if (isWizardBasicDone(this)) return WizardState.DONE;
        String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_WIZARD_LAST_STATE, WizardState.WELCOME.name());
        try { return WizardState.valueOf(name); } catch (IllegalArgumentException e) { return WizardState.WELCOME; }
    }

    private void markStateDone(WizardState state) {
        String key = state.prefsKey();
        if (key != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(key, true).apply();
        }
    }

    private boolean isStateDone(WizardState state) {
        String key = state.prefsKey();
        if (key == null) return false;
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, false);
    }

    private void markWizardBasicDone() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_WIZARD_BASIC_DONE, true).apply();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Step renderers (UI only — no navigation logic here)
    // ──────────────────────────────────────────────────────────────────────────

    private void showWelcomeStep() {
        addTitle(R.string.hermes_setup_welcome_title);
        addParagraph(R.string.hermes_setup_welcome_text);

        addSpacer(dp(16));
        Button ollamaBtn = new Button(this);
        ollamaBtn.setText(R.string.hermes_setup_quick_ollama);
        ollamaBtn.setTextSize(14);
        LinearLayout.LayoutParams ollamaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ollamaBtn.setLayoutParams(ollamaParams);
        ollamaBtn.setOnClickListener(v -> {
            mConfigManager.setModelProvider("ollama");
            mConfigManager.setModelName("llama3");
            jumpTo(WizardState.DONE);
        });
        mContentContainer.addView(ollamaBtn);
    }

    private void showLlmStep() {
        addTitle(R.string.hermes_setup_llm_title);
        addParagraph(R.string.hermes_setup_llm_text);

        String currentProvider = mConfigManager.getModelProvider();
        String[] providerValues = getResources().getStringArray(R.array.llm_provider_values);

        addLabel(getString(R.string.llm_provider_title));
        Spinner providerSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.llm_provider_names, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(adapter);
        mContentContainer.addView(providerSpinner);
        tagView(providerSpinner, "provider_spinner");

        for (int i = 0; i < providerValues.length; i++) {
            if (providerValues[i].equals(currentProvider)) {
                providerSpinner.setSelection(i);
                break;
            }
        }

        TextView providerBadge = new TextView(this);
        providerBadge.setTextSize(12);
        providerBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        providerBadge.setPadding(0, dp(4), 0, dp(4));
        updateProviderBadge(providerBadge, currentProvider);
        mContentContainer.addView(providerBadge);
        tagView(providerBadge, "provider_badge");

        Button setupGuideBtn = new Button(this);
        setupGuideBtn.setText(R.string.wizard_setup_guide_btn);
        setupGuideBtn.setTextSize(13);
        setupGuideBtn.setOnClickListener(v -> showSetupGuideForProvider(
                providerValues[providerSpinner.getSelectedItemPosition()]));
        LinearLayout.LayoutParams guideBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        guideBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        setupGuideBtn.setLayoutParams(guideBtnParams);
        mContentContainer.addView(setupGuideBtn);

        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String provider = providerValues[pos];
                updateModelSpinner(provider);
                updateProviderBadge(providerBadge, provider);
                mConfigManager.setModelProvider(provider);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        addSpacer(dp(16));
        addLabel(getString(R.string.llm_api_key_title));

        LinearLayout apiKeyRow = new LinearLayout(this);
        apiKeyRow.setOrientation(LinearLayout.HORIZONTAL);
        apiKeyRow.setGravity(Gravity.CENTER_VERTICAL);

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

        addSpacer(dp(16));
        addLabel(getString(R.string.llm_model_title));
        Spinner modelSpinner = new Spinner(this);
        mContentContainer.addView(modelSpinner);
        tagView(modelSpinner, "model_spinner");
        updateModelSpinner(currentProvider);

        TextView modelDesc = new TextView(this);
        modelDesc.setTextSize(13);
        modelDesc.setTextColor(0xFF666666);
        modelDesc.setPadding(dp(4), dp(4), dp(4), dp(8));
        mContentContainer.addView(modelDesc);
        tagView(modelDesc, "model_desc");
        updateModelDescription(mConfigManager.getModelName());

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Object selected = modelSpinner.getSelectedItem();
                if (selected != null) {
                    updateModelDescription(selected.toString());
                    mConfigManager.setModelName(selected.toString());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        addSpacer(dp(16));
        Button testBtn = new Button(this);
        testBtn.setText(R.string.llm_test_connection_title);
        testBtn.setOnClickListener(v -> runWizardLlmTest(testBtn));
        LinearLayout.LayoutParams testBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        testBtn.setLayoutParams(testBtnParams);
        mContentContainer.addView(testBtn);
        tagView(testBtn, "test_btn");

        TextView testStatus = new TextView(this);
        testStatus.setPadding(0, dp(8), 0, 0);
        testStatus.setTextSize(13);
        testStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        mContentContainer.addView(testStatus);
        tagView(testStatus, "test_status");
    }

    private void showImStep() {
        addTitle(R.string.hermes_setup_im_title);
        addParagraph(R.string.hermes_setup_im_text);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        addSpacer(dp(8));
        Button feishuBtn = new Button(this);
        feishuBtn.setText(R.string.hermes_setup_open_feishu_wizard);
        feishuBtn.setOnClickListener(v -> startActivity(new Intent(this, FeishuSetupActivity.class)));
        feishuBtn.setLayoutParams(btnParams);
        mContentContainer.addView(feishuBtn);

        Button telegramBtn = new Button(this);
        telegramBtn.setText(R.string.hermes_telegram_setup_title);
        telegramBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_TELEGRAM);
            startActivity(intent);
        });
        telegramBtn.setLayoutParams(btnParams);
        mContentContainer.addView(telegramBtn);

        Button discordBtn = new Button(this);
        discordBtn.setText(R.string.hermes_discord_setup_title);
        discordBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImSetupActivity.class);
            intent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_DISCORD);
            startActivity(intent);
        });
        discordBtn.setLayoutParams(btnParams);
        mContentContainer.addView(discordBtn);

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
        addTitle(R.string.hermes_setup_start_title);
        addParagraph(R.string.hermes_setup_start_text);

        addSpacer(dp(8));
        Button startBtn = new Button(this);
        startBtn.setText(R.string.gateway_start_title);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = Gravity.CENTER_HORIZONTAL;
        startBtn.setLayoutParams(btnParams);
        mContentContainer.addView(startBtn);
        tagView(startBtn, "start_btn");

        TextView statusText = new TextView(this);
        statusText.setPadding(0, dp(16), 0, 0);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        mContentContainer.addView(statusText);
        tagView(statusText, "start_status");

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
        addTitle(R.string.hermes_setup_done_title);
        addParagraph(R.string.hermes_setup_done_text);

        String provider = mConfigManager.getModelProvider();
        String apiKey = mConfigManager.getApiKey(provider);
        boolean hasLLM = !apiKey.isEmpty() || "ollama".equals(provider);
        boolean hasFeishu = mConfigManager.isFeishuConfigured();
        boolean hasTelegram = !mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty();
        boolean hasDiscord = !mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty();
        boolean hasWhatsApp = !mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty();

        StringBuilder summary = new StringBuilder();
        summary.append(hasLLM
                ? getString(R.string.hermes_setup_summary_ai, provider + " / " + mConfigManager.getModelName())
                : getString(R.string.hermes_setup_summary_ai_not_configured));
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

        addSpacer(dp(24));
        Button reconfigBtn = new Button(this);
        reconfigBtn.setText(R.string.hermes_setup_reconfigure);
        LinearLayout.LayoutParams reconfigParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        reconfigParams.gravity = Gravity.CENTER_HORIZONTAL;
        reconfigBtn.setLayoutParams(reconfigParams);
        reconfigBtn.setOnClickListener(v -> {
            resetStepTracking();
            transitionTo(WizardState.WELCOME, true);
        });
        mContentContainer.addView(reconfigBtn);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LLM helpers (read from UI fields — used by state machine validate/exit)
    // ──────────────────────────────────────────────────────────────────────────

    /** Read the currently selected provider from the LLM step UI. */
    String readCurrentProvider() {
        Spinner spinner = findTaggedView("provider_spinner");
        if (spinner == null) return mConfigManager.getModelProvider();
        String[] values = getResources().getStringArray(R.array.llm_provider_values);
        int pos = spinner.getSelectedItemPosition();
        return (pos >= 0 && pos < values.length) ? values[pos] : mConfigManager.getModelProvider();
    }

    /** Read the API key from the LLM step UI, falling back to saved config. */
    String readCurrentApiKey(String provider) {
        EditText input = findTaggedView("api_key_input");
        String key = (input != null) ? input.getText().toString().trim() : "";
        if (key.isEmpty()) key = mConfigManager.getApiKey(provider);
        return key;
    }

    private void saveLlmConfig() {
        Spinner providerSpinner = findTaggedView("provider_spinner");
        EditText apiKeyInput = findTaggedView("api_key_input");
        Spinner modelSpinner = findTaggedView("model_spinner");

        String[] providerValues = getResources().getStringArray(R.array.llm_provider_values);
        String provider = null;
        if (providerSpinner != null) {
            int pos = providerSpinner.getSelectedItemPosition();
            if (pos >= 0 && pos < providerValues.length) {
                provider = providerValues[pos];
                mConfigManager.setModelProvider(provider);
            }
        }
        if (apiKeyInput != null) {
            String key = apiKeyInput.getText().toString().trim();
            if (!key.isEmpty()) {
                String target = provider != null ? provider : mConfigManager.getModelProvider();
                mConfigManager.setApiKey(target, key);
            }
        }
        if (modelSpinner != null) {
            Object selected = modelSpinner.getSelectedItem();
            if (selected != null && !selected.toString().isEmpty()) {
                mConfigManager.setModelName(selected.toString());
            }
        }
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
                if (models[i].equals(currentModel)) { modelSpinner.setSelection(i); break; }
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

    private void updateProviderBadge(TextView badge, String provider) {
        if (badge == null) return;
        switch (provider) {
            case "openai": case "anthropic":
                badge.setText(getString(R.string.wizard_provider_recommended) + " • " + getString(R.string.provider_setup_paid));
                badge.setTextColor(0xFF1565C0); break;
            case "ollama":
                badge.setText(getString(R.string.wizard_provider_free));
                badge.setTextColor(0xFF388E3C); break;
            case "deepseek":
                badge.setText(getString(R.string.wizard_provider_budget) + " • " + getString(R.string.provider_setup_paid));
                badge.setTextColor(0xFFF57C00); break;
            case "google":
                badge.setText(getString(R.string.provider_setup_free_tier) + " • " + getString(R.string.provider_setup_paid));
                badge.setTextColor(0xFF388E3C); break;
            default:
                badge.setText(getString(R.string.provider_setup_paid));
                badge.setTextColor(0xFF888888); break;
        }
    }

    private void updateModelDescription(String model) {
        TextView modelDesc = findTaggedView("model_desc");
        if (modelDesc == null) return;
        int descResId = getModelDescResId(model);
        if (descResId != 0) {
            modelDesc.setText(getString(R.string.wizard_model_desc_label) + " " + getString(descResId));
            modelDesc.setVisibility(View.VISIBLE);
        } else {
            modelDesc.setVisibility(View.GONE);
        }
    }

    private int getModelDescResId(String model) {
        if (model == null) return 0;
        switch (model) {
            case "gpt-4o": return R.string.model_desc_gpt_4o;
            case "gpt-4o-mini": return R.string.model_desc_gpt_4o_mini;
            case "o1": return R.string.model_desc_o1;
            case "o1-mini": return R.string.model_desc_o1_mini;
            case "o3-mini": return R.string.model_desc_o3_mini;
            case "gpt-4-turbo": return R.string.model_desc_gpt_4_turbo;
            case "claude-sonnet-4-6": return R.string.model_desc_claude_sonnet;
            case "claude-opus-4-7": return R.string.model_desc_claude_opus;
            case "claude-haiku-4-5": return R.string.model_desc_claude_haiku;
            case "gemini-2.5-pro": return R.string.model_desc_gemini_pro;
            case "gemini-2.5-flash": return R.string.model_desc_gemini_flash;
            case "gemini-2.0-flash": return R.string.model_desc_gemini_flash2;
            case "deepseek-chat": return R.string.model_desc_deepseek_chat;
            case "deepseek-reasoner": return R.string.model_desc_deepseek_reasoner;
            case "grok-3": return R.string.model_desc_grok_3;
            case "grok-3-mini": return R.string.model_desc_grok_3_mini;
            case "qwen-max": return R.string.model_desc_qwen_max;
            case "qwen-plus": return R.string.model_desc_qwen_plus;
            case "qwen-turbo": return R.string.model_desc_qwen_turbo;
            case "mistral-large-latest": return R.string.model_desc_mistral_large;
            case "mistral-medium-latest": return R.string.model_desc_mistral_medium;
            case "codestral-latest": return R.string.model_desc_codestral;
            case "meta/llama-3.3-70b-instruct": return R.string.model_desc_llama_70b;
            case "deepseek-ai/deepseek-r1": return R.string.model_desc_deepseek_r1_nvidia;
            case "google/gemma-2-27b-it": return R.string.model_desc_gemma_27b;
            case "nvidia/llama-3.1-nemotron-70b-instruct": return R.string.model_desc_nemotron_70b;
            case "llama3": return R.string.model_desc_llama3_local;
            case "qwen2.5": return R.string.model_desc_qwen25_local;
            case "deepseek-r1": return R.string.model_desc_deepseek_r1_local;
            case "gemma2": return R.string.model_desc_gemma2_local;
            default: return 0;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Setup guide dialogs
    // ──────────────────────────────────────────────────────────────────────────

    private void showSetupGuideForProvider(String provider) {
        float density = getResources().getDisplayMetrics().density;
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        layout.setPadding(pad, pad, pad, pad);

        int introResId = getProviderIntroResId(provider);
        if (introResId != 0) {
            TextView intro = new TextView(this);
            intro.setText(introResId);
            intro.setTextSize(14);
            intro.setPadding(0, 0, 0, (int) (12 * density));
            layout.addView(intro);
        }
        int stepsResId = getProviderStepsResId(provider);
        if (stepsResId != 0) {
            for (String step : getString(stepsResId).split("\n")) {
                TextView stepView = new TextView(this);
                stepView.setText(step);
                stepView.setTextSize(13);
                stepView.setPadding((int) (8 * density), (int) (4 * density), 0, (int) (4 * density));
                layout.addView(stepView);
            }
        }
        scrollView.addView(layout);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.provider_setup_guide_title, getProviderDisplayName(provider)))
                .setView(scrollView)
                .setPositiveButton(R.string.provider_setup_open_dashboard, (d, w) -> {
                    String url = getApiKeyUrlForProvider(provider);
                    if (url != null) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
                        catch (Exception ignored) {}
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int getProviderIntroResId(String provider) {
        switch (provider) {
            case "openai": return R.string.provider_setup_openai_intro;
            case "anthropic": return R.string.provider_setup_anthropic_intro;
            case "google": return R.string.provider_setup_google_intro;
            case "deepseek": return R.string.provider_setup_deepseek_intro;
            case "openrouter": return R.string.provider_setup_openrouter_intro;
            case "xai": return R.string.provider_setup_xai_intro;
            case "alibaba": return R.string.provider_setup_alibaba_intro;
            case "mistral": return R.string.provider_setup_mistral_intro;
            case "nvidia": return R.string.provider_setup_nvidia_intro;
            default: return R.string.provider_setup_custom_intro;
        }
    }

    private int getProviderStepsResId(String provider) {
        switch (provider) {
            case "openai": return R.string.provider_setup_openai_steps;
            case "anthropic": return R.string.provider_setup_anthropic_steps;
            case "google": return R.string.provider_setup_google_steps;
            case "deepseek": return R.string.provider_setup_deepseek_steps;
            case "openrouter": return R.string.provider_setup_openrouter_steps;
            case "xai": return R.string.provider_setup_xai_steps;
            case "alibaba": return R.string.provider_setup_alibaba_steps;
            case "mistral": return R.string.provider_setup_mistral_steps;
            case "nvidia": return R.string.provider_setup_nvidia_steps;
            default: return R.string.provider_setup_custom_steps;
        }
    }

    private String getProviderDisplayName(String provider) {
        switch (provider) {
            case "openai": return "OpenAI"; case "anthropic": return "Anthropic";
            case "google": return "Google AI"; case "deepseek": return "DeepSeek";
            case "openrouter": return "OpenRouter"; case "xai": return "xAI";
            case "alibaba": return "Alibaba Cloud"; case "mistral": return "Mistral AI";
            case "nvidia": return "NVIDIA"; case "ollama": return "Ollama";
            default: return provider;
        }
    }

    private String getApiKeyUrlForProvider(String provider) {
        switch (provider) {
            case "openai": return "https://platform.openai.com/api-keys";
            case "anthropic": return "https://console.anthropic.com/settings/keys";
            case "google": return "https://aistudio.google.com/apikey";
            case "deepseek": return "https://platform.deepseek.com/api_keys";
            case "openrouter": return "https://openrouter.ai/keys";
            case "xai": return "https://console.x.ai/";
            case "alibaba": return "https://dashscope.console.aliyun.com/apiKey";
            case "mistral": return "https://console.mistral.ai/api-keys/";
            case "nvidia": return "https://build.nvidia.com/";
            default: return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LLM connection test
    // ──────────────────────────────────────────────────────────────────────────

    private void runWizardLlmTest(Button testBtn) {
        saveLlmConfig();
        String provider = mConfigManager.getModelProvider();
        String apiKey = readCurrentApiKey(provider);
        String model = mConfigManager.getModelName();
        TextView testStatus = findTaggedView("test_status");

        if ("ollama".equals(provider)) { apiKey = "ollama"; }
        else if (apiKey.isEmpty()) { if (testStatus != null) testStatus.setText(R.string.llm_test_no_key); return; }

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
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}", "--connect-timeout", "5", url);
            } else if ("google".equals(provider)) {
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}", "--connect-timeout", "10", url + apiKey);
            } else {
                pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null", "-w", "%{http_code}", "--connect-timeout", "10", "-H", "Authorization: Bearer " + apiKey, url);
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
        } catch (Exception e) { return new String[]{"network", e.getMessage()}; }
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

    // ──────────────────────────────────────────────────────────────────────────
    // Public API (unchanged)
    // ──────────────────────────────────────────────────────────────────────────

    public static boolean isWizardBasicDone(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WIZARD_BASIC_DONE, false);
    }

    public static boolean needsSetup(Context context) {
        return HermesConfigManager.getInstance().getConfigStatus() == HermesConfigManager.ConfigStatus.EMPTY
                && !isWizardBasicDone(context);
    }

    public static void resetStepTracking(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_WIZARD_BASIC_DONE)
                .remove(KEY_WIZARD_LAST_STATE)
                .remove("step_llm_done")
                .remove("step_im_done")
                .remove("step_start_done")
                .remove("welcome_shown")
                .apply();
    }

    private void resetStepTracking() { resetStepTracking(this); }

    // Backward compat
    public static boolean isWizardCompleted(Context context) { return isWizardBasicDone(context); }
    public static boolean isWizardDismissed(Context context) { return false; }
    public static void clearDismissedFlag(Context context) {}

    // ──────────────────────────────────────────────────────────────────────────
    // UI utilities
    // ──────────────────────────────────────────────────────────────────────────

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
        return (T) findTaggedViewRecursive(mContentContainer, tag);
    }

    private View findTaggedViewRecursive(ViewGroup parent, String tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
            if (child instanceof ViewGroup) {
                View found = findTaggedViewRecursive((ViewGroup) child, tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void tagView(View view, String tag) { view.setTag(tag); }

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

    private String formatPlatformStatus(String name, boolean configured) {
        return configured
                ? getString(R.string.hermes_setup_summary_platform_configured, name)
                : getString(R.string.hermes_setup_summary_platform_not_configured, name);
    }
}
