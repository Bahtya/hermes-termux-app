package com.termux.app.hermes.license;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;

/**
 * 许可证激活界面
 * 编程构建 UI，不使用 XML 布局
 */
public class LicenseActivationActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGE = "gate_message";

    private EditText mKeyInput;
    private Button mBtnActivate;
    private Button mBtnPaste;
    private Button mBtnDeactivate;
    private ProgressBar mProgressBar;
    private TextView mStatusText;
    private TextView mDeviceInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F0F1A);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("许可证激活");
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setBackgroundColor(0xFF1A1A2E);
        setSupportActionBar(toolbar);
        int toolbarHeight = (int) (56 * getResources().getDisplayMetrics().density);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight));
        root.addView(toolbar);

        // Scrollable content
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));

        int accentColor = 0xFF00BFA5; // Hermes Teal

        // Gate message
        String gateMessage = getIntent().getStringExtra(EXTRA_MESSAGE);
        if (gateMessage != null) {
            TextView gateMsg = new TextView(this);
            gateMsg.setText(gateMessage);
            gateMsg.setTextColor(0xFFFF8A65);
            gateMsg.setTextSize(14);
            gateMsg.setPadding(0, 0, 0, dp(16));
            content.addView(gateMsg);
        }

        // Check current license status
        boolean isLicensed = LicenseManager.getInstance().isLicensed(this);

        // Status text
        mStatusText = new TextView(this);
        mStatusText.setTextSize(14);
        mStatusText.setPadding(0, 0, 0, dp(12));
        if (isLicensed) {
            mStatusText.setText("✓ 已激活");
            mStatusText.setTextColor(0xFF4CAF50);
        } else {
            mStatusText.setText("请输入许可证密钥以激活应用");
            mStatusText.setTextColor(0xFFCCCCCC);
        }
        content.addView(mStatusText);

        // Device info
        mDeviceInfo = new TextView(this);
        mDeviceInfo.setTextSize(12);
        mDeviceInfo.setTextColor(0xFF888888);
        String fingerprint = DeviceFingerprint.compute(this);
        mDeviceInfo.setText("设备: " + DeviceFingerprint.getDeviceName()
                + "\n指纹: " + fingerprint.substring(0, 12) + "...");
        mDeviceInfo.setPadding(0, 0, 0, dp(16));
        content.addView(mDeviceInfo);

        // Key input (only show if not licensed)
        if (!isLicensed) {
            TextView label = new TextView(this);
            label.setText("许可证密钥");
            label.setTextColor(0xFFCCCCCC);
            label.setTextSize(13);
            label.setPadding(0, 0, 0, dp(4));
            content.addView(label);

            mKeyInput = new EditText(this);
            mKeyInput.setHint("HRMX-XXXX-XXXX-XXXX-XXXX");
            mKeyInput.setTextColor(0xFFFFFFFF);
            mKeyInput.setHintTextColor(0xFF666666);
            mKeyInput.setBackgroundColor(0xFF2A2A3E);
            mKeyInput.setPadding(dp(12), dp(12), dp(12), dp(12));
            mKeyInput.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
            mKeyInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            mKeyInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doActivate();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            inputParams.bottomMargin = dp(12);
            mKeyInput.setLayoutParams(inputParams);
            content.addView(mKeyInput);

            // Paste button
            mBtnPaste = new Button(this);
            mBtnPaste.setText("粘贴");
            mBtnPaste.setTextColor(0xFFFFFFFF);
            mBtnPaste.setBackgroundColor(0xFF3A3A4E);
            mBtnPaste.setOnClickListener(v -> pasteFromClipboard());
            LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pasteParams.bottomMargin = dp(16);
            pasteParams.gravity = Gravity.END;
            mBtnPaste.setLayoutParams(pasteParams);
            content.addView(mBtnPaste);

            // Activate button
            mBtnActivate = new Button(this);
            mBtnActivate.setText("激活");
            mBtnActivate.setTextColor(0xFFFFFFFF);
            mBtnActivate.setBackgroundColor(accentColor);
            mBtnActivate.setOnClickListener(v -> doActivate());
            LinearLayout.LayoutParams activateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            activateParams.bottomMargin = dp(12);
            mBtnActivate.setLayoutParams(activateParams);
            content.addView(mBtnActivate);
        }

        // Progress bar (hidden by default)
        mProgressBar = new ProgressBar(this);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);
        content.addView(mProgressBar);

        // Deactivate button (only if licensed)
        if (isLicensed) {
            mBtnDeactivate = new Button(this);
            mBtnDeactivate.setText("解除绑定");
            mBtnDeactivate.setTextColor(0xFFFF8A65);
            mBtnDeactivate.setBackgroundColor(0xFF2A2A3E);
            mBtnDeactivate.setOnClickListener(v -> doDeactivate());
            LinearLayout.LayoutParams deactivateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mBtnDeactivate.setLayoutParams(deactivateParams);
            content.addView(mBtnDeactivate);

            // Offline info
            int daysOffline = LicenseManager.getInstance().getDaysOffline(this);
            if (daysOffline > 0) {
                TextView offlineInfo = new TextView(this);
                offlineInfo.setText("上次验证: " + daysOffline + " 天前");
                offlineInfo.setTextColor(0xFF888888);
                offlineInfo.setTextSize(12);
                offlineInfo.setPadding(0, dp(16), 0, 0);
                content.addView(offlineInfo);
            }
        }

        scrollView.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f);
        scrollView.setLayoutParams(scrollParams);
        root.addView(scrollView);

        setContentView(root);

        // Prevent back navigation when not licensed
        getOnBackPressedDispatcher().setOnBackCallback(
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (LicenseManager.getInstance().isLicensed(
                                LicenseActivationActivity.this)) {
                            setEnabled(false);
                            onBackPressed();
                        } else {
                            moveTaskToBack(true);
                        }
                    }
                });
    }

    private void doActivate() {
        if (mKeyInput == null) return;
        String key = mKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            mStatusText.setText("请输入许可证密钥");
            mStatusText.setTextColor(0xFFFF8A65);
            return;
        }

        setLoading(true);
        mStatusText.setText("正在激活...");
        mStatusText.setTextColor(0xFFCCCCCC);

        LicenseManager.getInstance().activate(this, key, new LicenseManager.LicenseCallback() {
            @Override
            public void onSuccess(LicenseInfo info) {
                runOnUiThread(() -> {
                    setLoading(false);
                    mStatusText.setText("✓ 激活成功！");
                    mStatusText.setTextColor(0xFF4CAF50);
                    setResult(RESULT_OK);
                    finish();
                });
            }

            @Override
            public void onError(String code, String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    mStatusText.setText("✗ " + message);
                    mStatusText.setTextColor(0xFFFF8A65);
                });
            }
        });
    }

    private void doDeactivate() {
        setLoading(true);
        mStatusText.setText("正在解除绑定...");
        mStatusText.setTextColor(0xFFCCCCCC);

        LicenseManager.getInstance().deactivate(this, new LicenseManager.LicenseCallback() {
            @Override
            public void onSuccess(LicenseInfo info) {
                runOnUiThread(() -> {
                    setLoading(false);
                    // Restart activity to show activation UI
                    recreate();
                });
            }

            @Override
            public void onError(String code, String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    // Even on network error, local data is cleared
                    recreate();
                });
            }
        });
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && mKeyInput != null) {
                    mKeyInput.setText(text.toString().trim());
                }
            }
        }
    }

    private void setLoading(boolean loading) {
        if (mProgressBar != null) {
            mProgressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (mBtnActivate != null) {
            mBtnActivate.setEnabled(!loading);
        }
        if (mBtnDeactivate != null) {
            mBtnDeactivate.setEnabled(!loading);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
