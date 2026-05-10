package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.R;

/**
 * A step-by-step wizard Activity for configuring Feishu/Lark bot integration.
 *
 * Steps:
 *   0 - Welcome
 *   1 - Choose Domain (Feishu vs Lark)
 *   2 - Create App (instructions + link to developer console)
 *   3 - Enter Credentials (App ID + App Secret)
 *   4 - Configure Bot (connection mode + QR code placeholder)
 *   5 - Complete (summary + save)
 */
public class FeishuSetupActivity extends AppCompatActivity {

    private static final int TOTAL_STEPS = 6;

    // Wizard state
    private int mCurrentStep = 0;
    private boolean mIsLark = false;          // false = Feishu (飞书), true = Lark
    private String mAppId = "";
    private String mAppSecret = "";
    private boolean mUseWebhook = false;       // false = WebSocket, true = Webhook

    // Views
    private ViewPager mViewPager;
    private View[] mDots;
    private TextView mStepText;
    private MaterialButton mBtnNext;
    private MaterialButton mBtnBack;
    private MaterialButton mBtnCancel;

    // Step 4 views (configure)
    private RadioButton mRadioWebsocket;
    private RadioButton mRadioWebhook;
    private ImageView mQrCodeImage;
    private TextView mQrPlaceholder;

    // Step 3 views (credentials)
    private TextInputEditText mInputAppId;
    private TextInputEditText mInputAppSecret;
    private TextView mCredentialsError;

    // Config manager
    private HermesConfigManager mConfigManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feishu_setup);

        mConfigManager = HermesConfigManager.getInstance();

        // Toolbar back navigation
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> showCancelConfirmation());

        // Find views
        mViewPager = findViewById(R.id.view_pager);
        mStepText = findViewById(R.id.step_text);
        mBtnNext = findViewById(R.id.btn_next);
        mBtnBack = findViewById(R.id.btn_back);
        mBtnCancel = findViewById(R.id.btn_cancel);

        // Init step indicator dots
        initDots();

        // Setup ViewPager
        WizardPagerAdapter adapter = new WizardPagerAdapter(this);
        mViewPager.setAdapter(adapter);
        mViewPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageSelected(int position) {
                mCurrentStep = position;
                updateUI();
            }

            @Override
            public void onPageScrollStateChanged(int state) {}
        });

        // Disable swiping (force button navigation for validation control)
        mViewPager.setOnTouchListener((v, event) -> true);

        // Button listeners
        mBtnNext.setOnClickListener(v -> goNext());
        mBtnBack.setOnClickListener(v -> goBack());
        mBtnCancel.setOnClickListener(v -> showCancelConfirmation());

        updateUI();
    }

    @Override
    public void onBackPressed() {
        if (mCurrentStep > 0) {
            goBack();
        } else {
            showCancelConfirmation();
        }
    }

    // =========================================================================
    // Step indicator dots
    // =========================================================================

    private void initDots() {
        mDots = new View[TOTAL_STEPS];
        int[] dotIds = {
                R.id.dot_0, R.id.dot_1, R.id.dot_2,
                R.id.dot_3, R.id.dot_4, R.id.dot_5
        };
        for (int i = 0; i < TOTAL_STEPS; i++) {
            mDots[i] = findViewById(dotIds[i]);
        }
    }

    private void updateDots() {
        int activeColor = getResources().getColor(com.termux.shared.R.color.red_400, null);
        int completedColor = getResources().getColor(com.termux.shared.R.color.grey_500, null);
        int inactiveColor = getResources().getColor(com.termux.shared.R.color.grey_900, null);

        for (int i = 0; i < TOTAL_STEPS; i++) {
            if (mDots[i] != null) {
                if (i == mCurrentStep) {
                    mDots[i].setBackgroundColor(activeColor);
                    mDots[i].getLayoutParams().width = dpToPx(24);
                } else if (i < mCurrentStep) {
                    mDots[i].setBackgroundColor(completedColor);
                    mDots[i].getLayoutParams().width = dpToPx(10);
                } else {
                    mDots[i].setBackgroundColor(inactiveColor);
                    mDots[i].getLayoutParams().width = dpToPx(10);
                }
                mDots[i].requestLayout();
            }
        }
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void updateUI() {
        updateDots();
        mStepText.setText(getString(R.string.feishu_step_indicator, mCurrentStep + 1, TOTAL_STEPS));

        // Back button visibility
        mBtnBack.setVisibility(mCurrentStep > 0 ? View.VISIBLE : View.GONE);

        // Next button text and behavior
        if (mCurrentStep == TOTAL_STEPS - 1) {
            mBtnNext.setText(R.string.feishu_complete_save);
        } else {
            mBtnNext.setText(R.string.feishu_button_next);
        }
    }

    private void goNext() {
        // Validate credentials on step 3 before proceeding
        if (mCurrentStep == 3) {
            if (!validateCredentials()) {
                return;
            }
            collectCredentials();
        }

        if (mCurrentStep < TOTAL_STEPS - 1) {
            mCurrentStep++;
            mViewPager.setCurrentItem(mCurrentStep, true);
            updateUI();

            // Generate QR code when entering configure step
            if (mCurrentStep == 4) {
                generateQrCode();
            }
        }
    }

    private void goBack() {
        if (mCurrentStep > 0) {
            // Save current credentials before going back
            if (mCurrentStep == 3) {
                collectCredentials();
            }
            mCurrentStep--;
            mViewPager.setCurrentItem(mCurrentStep, true);
            updateUI();
        }
    }

    private void showCancelConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.feishu_confirm_cancel_title)
                .setMessage(R.string.feishu_confirm_cancel_message)
                .setPositiveButton(R.string.feishu_confirm_cancel_yes,
                        (dialog, which) -> finish())
                .setNegativeButton(R.string.feishu_confirm_cancel_no, null)
                .show();
    }

    // =========================================================================
    // Step 3: Credential validation
    // =========================================================================

    private boolean validateCredentials() {
        if (mInputAppId == null || mInputAppSecret == null) return true;

        String appId = getTextFromEditText(mInputAppId);
        String appSecret = getTextFromEditText(mInputAppSecret);

        if (TextUtils.isEmpty(appId) || TextUtils.isEmpty(appSecret)) {
            showCredentialError(getString(R.string.feishu_credentials_error_empty));
            return false;
        }

        if (!appId.startsWith("cli_")) {
            showCredentialError(getString(R.string.feishu_credentials_error_invalid_id));
            return false;
        }

        hideCredentialError();
        return true;
    }

    private void collectCredentials() {
        if (mInputAppId != null) {
            mAppId = getTextFromEditText(mInputAppId);
        }
        if (mInputAppSecret != null) {
            mAppSecret = getTextFromEditText(mInputAppSecret);
        }
    }

    private void showCredentialError(String message) {
        if (mCredentialsError != null) {
            mCredentialsError.setText(message);
            mCredentialsError.setVisibility(View.VISIBLE);
        }
    }

    private void hideCredentialError() {
        if (mCredentialsError != null) {
            mCredentialsError.setVisibility(View.GONE);
        }
    }

    @NonNull
    private static String getTextFromEditText(@NonNull EditText editText) {
        CharSequence text = editText.getText();
        return text != null ? text.toString().trim() : "";
    }

    // =========================================================================
    // Step 4: QR code generation (visual placeholder from credentials)
    // =========================================================================

    private void generateQrCode() {
        if (mQrCodeImage == null) return;

        // Generate a visual QR-code-like bitmap from the app credentials.
        // In production, replace with a proper QR encoding library (ZXing, etc.)
        String content = "feishu://" + mAppId + "|" + (mIsLark ? "lark" : "feishu");
        int size = dpToPx(200);
        Bitmap qrBitmap = generatePlaceholderQr(content, size);

        if (qrBitmap != null) {
            mQrCodeImage.setImageBitmap(qrBitmap);
            mQrCodeImage.setVisibility(View.VISIBLE);
            if (mQrPlaceholder != null) {
                mQrPlaceholder.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Generates a deterministic QR-like bitmap pattern from the content string.
     * Includes finder patterns to visually resemble a real QR code.
     * A proper QR encoding library should replace this in production.
     */
    private Bitmap generatePlaceholderQr(String content, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_888B);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint darkPaint = new Paint();
        darkPaint.setColor(Color.BLACK);
        darkPaint.setStyle(Paint.Style.FILL);
        darkPaint.setAntiAlias(false);

        int gridSize = 21;
        int cellSize = size / gridSize;

        // Draw finder patterns (the three large squares in QR corners)
        drawFinderPattern(canvas, darkPaint, 0, 0, cellSize);
        drawFinderPattern(canvas, darkPaint, (gridSize - 7) * cellSize, 0, cellSize);
        drawFinderPattern(canvas, darkPaint, 0, (gridSize - 7) * cellSize, cellSize);

        // Generate deterministic data pattern from content
        int hash = content.hashCode();
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                // Skip finder pattern areas
                if (isFinderArea(row, col)) continue;

                // Deterministic fill from content hash and character data
                int charIndex = row * gridSize + col;
                boolean filled;
                if (charIndex < content.length()) {
                    filled = (content.charAt(charIndex) % 2) != 0;
                } else {
                    int bitIndex = charIndex % 32;
                    filled = ((hash >> Math.abs(bitIndex - 1)) & 1) == 1;
                }

                if (filled) {
                    canvas.drawRect(
                            col * cellSize, row * cellSize,
                            (col + 1) * cellSize, (row + 1) * cellSize,
                            darkPaint);
                }
            }
        }

        return bitmap;
    }

    private void drawFinderPattern(Canvas canvas, Paint paint, int x, int y, int cellSize) {
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                // Outer border or inner 3x3 solid square
                boolean isBorder = (i == 0 || i == 6 || j == 0 || j == 6);
                boolean isInner = (i >= 2 && i <= 4 && j >= 2 && j <= 4);
                if (isBorder || isInner) {
                    canvas.drawRect(
                            x + j * cellSize, y + i * cellSize,
                            x + (j + 1) * cellSize, y + (i + 1) * cellSize,
                            paint);
                }
            }
        }
    }

    private boolean isFinderArea(int row, int col) {
        // Top-left 8x8 (includes separator)
        if (row < 8 && col < 8) return true;
        // Top-right 8x8
        if (row < 8 && col >= 13) return true;
        // Bottom-left 8x8
        if (row >= 13 && col < 8) return true;
        return false;
    }

    // =========================================================================
    // Save configuration and finish
    // =========================================================================

    private void saveAndFinish() {
        try {
            collectCredentials();

            String connectionMode = mUseWebhook ? "webhook" : "websocket";
            String domain = mIsLark ? "lark" : "feishu";

            // Save using HermesConfigManager (6-param version)
            mConfigManager.setFeishuConfig(
                    mAppId,            // appId
                    mAppSecret,        // appSecret
                    domain,            // domain
                    connectionMode,    // connectionMode
                    null,              // allowedUsers (keep existing)
                    null               // homeChannel (keep existing)
            );

            Toast.makeText(this, R.string.feishu_config_saved, Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();

        } catch (Exception e) {
            Toast.makeText(this, R.string.feishu_config_error, Toast.LENGTH_LONG).show();
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // ViewPager Adapter
    // =========================================================================

    private class WizardPagerAdapter extends PagerAdapter {

        private final Context mContext;

        WizardPagerAdapter(Context context) {
            mContext = context;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            LayoutInflater inflater = LayoutInflater.from(mContext);
            View view;

            switch (position) {
                case 0:
                    view = inflater.inflate(R.layout.step_feishu_welcome, container, false);
                    break;
                case 1:
                    view = inflater.inflate(R.layout.step_feishu_domain, container, false);
                    setupDomainStep(view);
                    break;
                case 2:
                    view = inflater.inflate(R.layout.step_feishu_create_app, container, false);
                    setupCreateAppStep(view);
                    break;
                case 3:
                    view = inflater.inflate(R.layout.step_feishu_credentials, container, false);
                    setupCredentialsStep(view);
                    break;
                case 4:
                    view = inflater.inflate(R.layout.step_feishu_configure, container, false);
                    setupConfigureStep(view);
                    break;
                case 5:
                    view = inflater.inflate(R.layout.step_feishu_complete, container, false);
                    break;
                default:
                    view = new View(mContext);
                    break;
            }

            container.addView(view);
            return view;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Override
        public int getCount() {
            return TOTAL_STEPS;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }
    }

    // =========================================================================
    // Step setup helpers
    // =========================================================================

    private void setupDomainStep(View view) {
        RadioButton radioFeishu = view.findViewById(R.id.radio_feishu);
        RadioButton radioLark = view.findViewById(R.id.radio_lark);
        MaterialCardView cardFeishu = view.findViewById(R.id.card_feishu);
        MaterialCardView cardLark = view.findViewById(R.id.card_lark);

        // Restore selection
        radioFeishu.setChecked(!mIsLark);
        radioLark.setChecked(mIsLark);
        updateDomainCardSelection(cardFeishu, cardLark, !mIsLark);

        View.OnClickListener feishuClick = v -> {
            mIsLark = false;
            radioFeishu.setChecked(true);
            radioLark.setChecked(false);
            updateDomainCardSelection(cardFeishu, cardLark, true);
        };

        View.OnClickListener larkClick = v -> {
            mIsLark = true;
            radioFeishu.setChecked(false);
            radioLark.setChecked(true);
            updateDomainCardSelection(cardFeishu, cardLark, false);
        };

        cardFeishu.setOnClickListener(feishuClick);
        cardLark.setOnClickListener(larkClick);
        radioFeishu.setOnClickListener(feishuClick);
        radioLark.setOnClickListener(larkClick);
    }

    private void updateDomainCardSelection(MaterialCardView cardFeishu,
                                           MaterialCardView cardLark,
                                           boolean feishuSelected) {
        int activeColor = getResources().getColor(com.termux.shared.R.color.red_400, null);
        int inactiveColor = getResources().getColor(com.termux.shared.R.color.grey_500, null);

        cardFeishu.setStrokeColor(feishuSelected ? activeColor : inactiveColor);
        cardFeishu.setStrokeWidth(feishuSelected ? 3 : 1);

        cardLark.setStrokeColor(!feishuSelected ? activeColor : inactiveColor);
        cardLark.setStrokeWidth(!feishuSelected ? 3 : 1);
    }

    private void setupCreateAppStep(View view) {
        MaterialButton btnOpenConsole = view.findViewById(R.id.btn_open_console);
        btnOpenConsole.setOnClickListener(v -> {
            String url = mIsLark
                    ? "https://open.larksuite.com/app"
                    : "https://open.feishu.cn/app";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        });
    }

    private void setupCredentialsStep(View view) {
        mInputAppId = view.findViewById(R.id.input_app_id);
        mInputAppSecret = view.findViewById(R.id.input_app_secret);
        mCredentialsError = view.findViewById(R.id.credentials_error);

        // Restore previously entered values (from wizard state or saved config)
        if (!TextUtils.isEmpty(mAppId)) {
            mInputAppId.setText(mAppId);
        } else if (mConfigManager != null) {
            String savedAppId = mConfigManager.getFeishuAppId();
            if (!TextUtils.isEmpty(savedAppId)) {
                mInputAppId.setText(savedAppId);
                mAppId = savedAppId;
            }
        }

        if (!TextUtils.isEmpty(mAppSecret)) {
            mInputAppSecret.setText(mAppSecret);
        } else if (mConfigManager != null) {
            String savedSecret = mConfigManager.getFeishuAppSecret();
            if (!TextUtils.isEmpty(savedSecret)) {
                mInputAppSecret.setText(savedSecret);
                mAppSecret = savedSecret;
            }
        }

        // Clear error on text change
        mInputAppId.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                hideCredentialError();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        mInputAppSecret.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                hideCredentialError();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void setupConfigureStep(View view) {
        mRadioWebsocket = view.findViewById(R.id.radio_websocket);
        mRadioWebhook = view.findViewById(R.id.radio_webhook);
        mQrCodeImage = view.findViewById(R.id.qr_code_image);
        mQrPlaceholder = view.findViewById(R.id.qr_code_placeholder);

        MaterialCardView cardWebsocket = view.findViewById(R.id.card_websocket);
        MaterialCardView cardWebhook = view.findViewById(R.id.card_webhook);

        mRadioWebsocket.setChecked(!mUseWebhook);
        mRadioWebhook.setChecked(mUseWebhook);
        updateModeCardSelection(cardWebsocket, cardWebhook, !mUseWebhook);

        View.OnClickListener wsClick = v -> {
            mUseWebhook = false;
            mRadioWebsocket.setChecked(true);
            mRadioWebhook.setChecked(false);
            updateModeCardSelection(cardWebsocket, cardWebhook, true);
        };

        View.OnClickListener whClick = v -> {
            mUseWebhook = true;
            mRadioWebsocket.setChecked(false);
            mRadioWebhook.setChecked(true);
            updateModeCardSelection(cardWebsocket, cardWebhook, false);
        };

        cardWebsocket.setOnClickListener(wsClick);
        cardWebhook.setOnClickListener(whClick);
        mRadioWebsocket.setOnClickListener(wsClick);
        mRadioWebhook.setOnClickListener(whClick);

        // Generate QR code if credentials already entered
        if (!TextUtils.isEmpty(mAppId)) {
            generateQrCode();
        }
    }

    private void updateModeCardSelection(MaterialCardView cardWebsocket,
                                         MaterialCardView cardWebhook,
                                         boolean wsSelected) {
        int activeColor = getResources().getColor(com.termux.shared.R.color.red_400, null);
        int inactiveColor = getResources().getColor(com.termux.shared.R.color.grey_500, null);

        cardWebsocket.setStrokeColor(wsSelected ? activeColor : inactiveColor);
        cardWebsocket.setStrokeWidth(wsSelected ? 3 : 1);

        cardWebhook.setStrokeColor(!wsSelected ? activeColor : inactiveColor);
        cardWebhook.setStrokeWidth(!wsSelected ? 3 : 1);
    }
}
