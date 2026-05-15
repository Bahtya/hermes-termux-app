package com.termux.app.hermes;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
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
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lark.oapi.scene.registration.RegisterApp;
import com.lark.oapi.scene.registration.RegisterAppOptions;
import com.lark.oapi.scene.registration.RegisterAppResult;
import com.termux.R;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class FeishuSetupActivity extends AppCompatActivity {

    // Step constants
    private static final int STEP_WELCOME = 0;
    private static final int STEP_DOMAIN = 1;
    private static final int STEP_SCAN = 2;
    private static final int STEP_COMPLETE = 3;
    private static final int TOTAL_STEPS = 4;

    // Scan state machine
    enum ScanState {
        IDLE,
        CONNECTING,
        QR_READY,
        POLLING,
        SUCCESS,
        PROBING_BOT,
        COMPLETE,
        ERROR_INIT,
        ERROR_BEGIN,
        ERROR_NETWORK,
        ERROR_TIMEOUT,
        ERROR_DENIED,
        ERROR_PROBE
    }

    private int mCurrentStep = STEP_WELCOME;
    private String mDomain = "feishu";

    // Scan state
    private ScanState mScanState = ScanState.IDLE;
    private String mResultAppId;
    private String mResultAppSecret;
    private String mResultDomain;
    private String mBotName;
    private String mOpenId;
    private int mExpireIn;
    private long mPollStartTime;
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);
    private Future<?> mRegistrationFuture;

    // UI references (scan step)
    private ImageView mQrImageView;
    private ProgressBar mScanProgress;
    private TextView mScanStatusText;
    private TextView mScanCountdown;
    private com.google.android.material.button.MaterialButton mScanActionBtn;

    private LinearLayout mStepContainer;
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
        cancelRegistration();
        mExecutor.shutdownNow();
    }

    // =========================================================================
    // Step navigation
    // =========================================================================

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

        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setSubtitle(getString(R.string.feishu_step_progress, step + 1, TOTAL_STEPS));
        }
    }

    private View createStepView(int step) {
        switch (step) {
            case STEP_WELCOME: return createWelcomeStep();
            case STEP_DOMAIN: return createDomainStep();
            case STEP_SCAN: return createScanStep();
            case STEP_COMPLETE: return createCompleteStep();
            default: return createWelcomeStep();
        }
    }

    private void nextStep() {
        if (mCurrentStep == STEP_COMPLETE) {
            finish();
            return;
        }
        if (mCurrentStep == STEP_SCAN) {
            if (mScanState != ScanState.COMPLETE && mScanState != ScanState.ERROR_PROBE) {
                Toast.makeText(this, R.string.feishu_scan_not_ready, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // showStep() updates mCurrentStep, so the next check uses the new value
        showStep(mCurrentStep + 1);
        if (mCurrentStep == STEP_SCAN) {
            startRegistration();
        }
    }

    private void prevStep() {
        if (mCurrentStep == STEP_SCAN) {
            cancelRegistration();
        }
        if (mCurrentStep > STEP_WELCOME) {
            showStep(mCurrentStep - 1);
        }
    }

    // =========================================================================
    // Step 0: Welcome
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
        descTv.setText(R.string.feishu_scan_welcome_desc);
        descTv.setTextSize(15);
        descTv.setLineSpacing(dp(4), 1f);
        ll.addView(descTv);

        addSpacer(ll, dp(20));

        TextView stepsTv = new TextView(this);
        stepsTv.setText(R.string.feishu_scan_welcome_steps);
        stepsTv.setTextSize(14);
        stepsTv.setLineSpacing(dp(4), 1f);
        stepsTv.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
        ll.addView(stepsTv);

        sv.addView(ll);
        return sv;
    }

    // =========================================================================
    // Step 1: Choose Domain
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
        feishuHint.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_hint));
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
        larkHint.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_hint));
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
    // Step 2: Scan QR Code (state machine driven)
    // =========================================================================

    private View createScanStep() {
        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        addStepHeader(ll, R.string.feishu_scan_step_title, R.string.feishu_scan_step_desc);

        // QR code image
        mQrImageView = new ImageView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(200), dp(200));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = dp(8);
        qrParams.bottomMargin = dp(8);
        mQrImageView.setLayoutParams(qrParams);
        mQrImageView.setId(R.id.feishu_qr_image);
        mQrImageView.setVisibility(View.GONE);
        ll.addView(mQrImageView);

        // Progress bar
        mScanProgress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(8);
        mScanProgress.setLayoutParams(progressParams);
        mScanProgress.setId(R.id.feishu_scan_progress);
        mScanProgress.setVisibility(View.GONE);
        ll.addView(mScanProgress);

        // Status text
        mScanStatusText = new TextView(this);
        mScanStatusText.setId(R.id.feishu_scan_status);
        mScanStatusText.setTextSize(14);
        mScanStatusText.setGravity(Gravity.CENTER_HORIZONTAL);
        mScanStatusText.setLineSpacing(dp(4), 1f);
        mScanStatusText.setPadding(0, dp(12), 0, dp(4));
        ll.addView(mScanStatusText);

        // Countdown
        mScanCountdown = new TextView(this);
        mScanCountdown.setId(R.id.feishu_scan_countdown);
        mScanCountdown.setTextSize(12);
        mScanCountdown.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_hint));
        mScanCountdown.setGravity(Gravity.CENTER_HORIZONTAL);
        mScanCountdown.setVisibility(View.GONE);
        ll.addView(mScanCountdown);

        addSpacer(ll, dp(12));

        // Action button (rescan / cancel)
        mScanActionBtn = new com.google.android.material.button.MaterialButton(this);
        mScanActionBtn.setId(R.id.feishu_scan_action_btn);
        mScanActionBtn.setAllCaps(false);
        mScanActionBtn.setCornerRadius(dp(20));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.CENTER_HORIZONTAL;
        mScanActionBtn.setLayoutParams(actionParams);
        mScanActionBtn.setVisibility(View.GONE);
        ll.addView(mScanActionBtn);

        sv.addView(ll);
        return sv;
    }

    private void updateScanUi(ScanState state) {
        mScanState = state;
        if (isFinishing() || mQrImageView == null) return;

        switch (state) {
            case IDLE:
                mQrImageView.setVisibility(View.GONE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText("");
                mScanCountdown.setVisibility(View.GONE);
                mScanActionBtn.setVisibility(View.GONE);
                break;

            case CONNECTING:
                mQrImageView.setVisibility(View.GONE);
                mScanProgress.setVisibility(View.VISIBLE);
                mScanStatusText.setText(R.string.feishu_scan_connecting);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
                mScanCountdown.setVisibility(View.GONE);
                showCancelButton();
                break;

            case QR_READY:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_hint);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
                mScanCountdown.setVisibility(View.GONE);
                showCancelButton();
                break;

            case POLLING:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.VISIBLE);
                mScanStatusText.setText(R.string.feishu_scan_waiting);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
                mScanCountdown.setVisibility(View.VISIBLE);
                showCancelButton();
                break;

            case SUCCESS:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.VISIBLE);
                mScanStatusText.setText(R.string.feishu_scan_success);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_success));
                mScanCountdown.setVisibility(View.GONE);
                mScanActionBtn.setVisibility(View.GONE);
                break;

            case PROBING_BOT:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.VISIBLE);
                mScanStatusText.setText(R.string.feishu_scan_probing);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_success));
                mScanCountdown.setVisibility(View.GONE);
                mScanActionBtn.setVisibility(View.GONE);
                break;

            case COMPLETE:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.GONE);
                String msg = mBotName != null && !mBotName.isEmpty()
                        ? getString(R.string.feishu_scan_complete_with_name, mBotName)
                        : getString(R.string.feishu_scan_complete);
                mScanStatusText.setText(msg);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_success));
                mScanCountdown.setVisibility(View.GONE);
                mScanActionBtn.setVisibility(View.GONE);
                break;

            case ERROR_INIT:
                mQrImageView.setVisibility(View.GONE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_error_init);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                showRescanButton();
                break;

            case ERROR_BEGIN:
                mQrImageView.setVisibility(View.GONE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_error_begin);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                showRescanButton();
                break;

            case ERROR_NETWORK:
                mQrImageView.setVisibility(View.GONE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_error_network);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                showRescanButton();
                break;

            case ERROR_TIMEOUT:
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_timeout);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                showRescanButton();
                break;

            case ERROR_DENIED:
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_denied);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                showRescanButton();
                break;

            case ERROR_PROBE:
                mQrImageView.setVisibility(View.VISIBLE);
                mScanProgress.setVisibility(View.GONE);
                mScanStatusText.setText(R.string.feishu_scan_error_probe);
                mScanStatusText.setTextColor(ContextCompat.getColor(this, R.color.hermes_validation_warning));
                mScanCountdown.setVisibility(View.GONE);
                mScanActionBtn.setVisibility(View.GONE);
                break;
        }
    }

    private void showCancelButton() {
        mScanActionBtn.setText(R.string.feishu_cancel);
        mScanActionBtn.setVisibility(View.VISIBLE);
        mScanActionBtn.setOnClickListener(v -> cancelRegistration());
    }

    private void showRescanButton() {
        mScanActionBtn.setText(R.string.feishu_rescan);
        mScanActionBtn.setVisibility(View.VISIBLE);
        mScanActionBtn.setOnClickListener(v -> startRegistration());
    }

    // =========================================================================
    // Registration flow (Feishu Java SDK)
    // =========================================================================

    private void startRegistration() {
        cancelRegistration();
        mCancelled.set(false);
        mResultAppId = null;
        mResultAppSecret = null;
        mResultDomain = null;
        mBotName = null;
        mOpenId = null;

        runOnUiThread(() -> updateScanUi(ScanState.CONNECTING));

        mRegistrationFuture = mExecutor.submit(() -> {
            try {
                String domainUrl = "lark".equals(mDomain)
                        ? "https://accounts.larksuite.com"
                        : "https://accounts.feishu.cn";

                RegisterAppResult result = RegisterApp.register(
                        RegisterAppOptions.newBuilder()
                                .source("hermes-termux")
                                .domain(domainUrl)
                                .onQRCode(info -> {
                                    if (mCancelled.get()) return;
                                    String url = info.getUrl();
                                    int expireIn = info.getExpireIn();
                                    if (url != null) {
                                        mExpireIn = expireIn;
                                        mPollStartTime = System.currentTimeMillis();
                                        runOnUiThread(() -> {
                                            if (isFinishing() || mCancelled.get()) return;
                                            Bitmap qr = generateQRCode(url, dp(200));
                                            if (qr != null) {
                                                mQrImageView.setImageBitmap(qr);
                                            }
                                            updateScanUi(ScanState.QR_READY);
                                            mQrImageView.postDelayed(() -> {
                                                if (!mCancelled.get() && !isFinishing()) {
                                                    updateScanUi(ScanState.POLLING);
                                                    startCountdown();
                                                }
                                            }, 500);
                                        });
                                    }
                                })
                                .onStatusChange(info -> {
                                    if (mCancelled.get()) return;
                                    runOnUiThread(() -> {
                                        if (isFinishing() || mCancelled.get()) return;
                                        if (mScanState == ScanState.QR_READY || mScanState == ScanState.CONNECTING) {
                                            updateScanUi(ScanState.POLLING);
                                            startCountdown();
                                        }
                                    });
                                })
                                .build()
                );

                if (mCancelled.get()) return;

                // Registration succeeded
                mResultAppId = result.getClientId();
                mResultAppSecret = result.getClientSecret();
                if (result.getUserInfo() != null) {
                    mOpenId = result.getUserInfo().getOpenId();
                }
                mResultDomain = mDomain;

                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    updateScanUi(ScanState.SUCCESS);
                });

                // Probe bot
                try {
                    String botName = probeBot(mResultAppId, mResultAppSecret, mResultDomain);
                    if (botName != null) {
                        mBotName = botName;
                    }
                } catch (Exception e) {
                    // Non-fatal
                }

                if (mCancelled.get()) return;

                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    updateScanUi(ScanState.COMPLETE);
                });

            } catch (Exception e) {
                if (mCancelled.get()) return;
                ScanState errorState = classifyError(e);
                ScanState finalError = errorState;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    updateScanUi(finalError);
                });
            }
        });
    }

    private void cancelRegistration() {
        mCancelled.set(true);
        if (mRegistrationFuture != null) {
            mRegistrationFuture.cancel(true);
            mRegistrationFuture = null;
        }
        // Reset state to stop countdown and clear UI
        mScanState = ScanState.IDLE;
    }

    private void startCountdown() {
        if (mScanCountdown == null) return;
        final int totalSeconds = mExpireIn > 0 ? mExpireIn : 600;
        mScanCountdown.post(new Runnable() {
            @Override
            public void run() {
                if (mScanState != ScanState.POLLING || isFinishing()) return;
                long elapsed = (System.currentTimeMillis() - mPollStartTime) / 1000;
                long remaining = totalSeconds - elapsed;
                if (remaining <= 0) {
                    mScanCountdown.setVisibility(View.GONE);
                    return;
                }
                long min = remaining / 60;
                long sec = remaining % 60;
                mScanCountdown.setText(getString(R.string.feishu_scan_countdown, min, sec));
                mScanCountdown.postDelayed(this, 1000);
            }
        });
    }

    private String probeBot(String appId, String appSecret, String domain) {
        try {
            runOnUiThread(() -> {
                if (!isFinishing()) updateScanUi(ScanState.PROBING_BOT);
            });

            String baseUrl = "lark".equals(domain)
                    ? "https://open.larksuite.com"
                    : "https://open.feishu.cn";

            // Get tenant_access_token
            java.net.URL tokenUrl = new java.net.URL(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal");
            java.net.HttpURLConnection tokenConn = (java.net.HttpURLConnection) tokenUrl.openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setRequestProperty("Content-Type", "application/json");
            tokenConn.setConnectTimeout(10000);
            tokenConn.setReadTimeout(10000);
            tokenConn.setDoOutput(true);
            org.json.JSONObject tokenBody = new org.json.JSONObject()
                    .put("app_id", appId)
                    .put("app_secret", appSecret);
            tokenConn.getOutputStream().write(tokenBody.toString().getBytes("UTF-8"));

            String tokenResponse = readHttpResponse(tokenConn);
            tokenConn.disconnect();

            org.json.JSONObject tokenJson = new org.json.JSONObject(tokenResponse);
            if (tokenJson.getInt("code") != 0) return null;
            String accessToken = tokenJson.getString("tenant_access_token");

            // Get bot info
            java.net.URL botUrl = new java.net.URL(baseUrl + "/open-apis/bot/v3/info/");
            java.net.HttpURLConnection botConn = (java.net.HttpURLConnection) botUrl.openConnection();
            botConn.setRequestMethod("GET");
            botConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            botConn.setConnectTimeout(10000);
            botConn.setReadTimeout(10000);

            String botResponse = readHttpResponse(botConn);
            botConn.disconnect();

            org.json.JSONObject botJson = new org.json.JSONObject(botResponse);
            if (botJson.getInt("code") == 0) {
                org.json.JSONObject bot = botJson.getJSONObject("bot");
                return bot.optString("app_name", null);
            }
        } catch (Exception e) {
            // Non-fatal
        }
        return null;
    }

    private ScanState classifyError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return ScanState.ERROR_TIMEOUT;
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) {
            return ScanState.ERROR_NETWORK;
        }
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String className = e.getClass().getName().toLowerCase();
        if (className.contains("timeout") || msg.contains("expired") || msg.contains("timeout")) {
            return ScanState.ERROR_TIMEOUT;
        }
        if (className.contains("connect") || className.contains("unknownhost")
                || className.contains("socket") || msg.contains("network")) {
            return ScanState.ERROR_NETWORK;
        }
        if (msg.contains("denied") || msg.contains("access_denied")) {
            return ScanState.ERROR_DENIED;
        }
        if (msg.contains("abort") || msg.contains("init")) {
            return ScanState.ERROR_INIT;
        }
        if (msg.contains("begin") || msg.contains("device_code") || msg.contains("invalid")) {
            return ScanState.ERROR_BEGIN;
        }
        return ScanState.ERROR_NETWORK;
    }

    private String readHttpResponse(java.net.HttpURLConnection conn) throws java.io.IOException {
        java.io.InputStream is = conn.getResponseCode() < 400
                ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // Step 3: Complete
    // =========================================================================

    private View createCompleteStep() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);

        // Save config
        mConfigManager.setFeishuConfig(mResultAppId, mResultAppSecret, mResultDomain,
                "websocket", null, null);

        mConfigManager.setEnvVar("FEISHU_APP_ID", mResultAppId);
        mConfigManager.setEnvVar("FEISHU_APP_SECRET", mResultAppSecret);
        mConfigManager.setEnvVar("FEISHU_DOMAIN", mResultDomain);
        mConfigManager.setEnvVar("FEISHU_CONNECTION_MODE", "websocket");
        mConfigManager.setEnvVar("FEISHU_ALLOW_ALL_USERS", "true");
        mConfigManager.setEnvVar("FEISHU_GROUP_POLICY", "open");
        mConfigManager.setEnvVar("FEISHU_ALLOWED_USERS", "");

        HermesConfigManager.ensureGatewayRunning(this);

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

        String domainDisplay = "feishu".equals(mResultDomain) ? "Feishu" : "Lark";
        String appIdDisplay = mResultAppId != null
                ? mResultAppId.substring(0, Math.min(12, mResultAppId.length())) + "..."
                : "";
        String botDisplay = mBotName != null ? mBotName : "";
        TextView summary = new TextView(this);
        summary.setText(getString(R.string.feishu_scan_complete_summary,
                botDisplay, domainDisplay, appIdDisplay));
        summary.setTextSize(14);
        summary.setLineSpacing(dp(4), 1f);
        summary.setPadding(dp(16), dp(16), dp(16), dp(16));
        summary.setBackgroundColor(ContextCompat.getColor(this, R.color.hermes_card_background));
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
            desc.setTextColor(ContextCompat.getColor(this, R.color.hermes_text_secondary));
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

    private ScrollView wrapInScrollView(View content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        return sv;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
