package com.termux.app.terminal;

import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.view.KeyboardUtils;
import com.termux.view.TerminalView;

public class TerminalDelegate {

    private static final String LOG_TAG = "TerminalDelegate";

    private final TermuxActivity mActivity;

    private boolean mTerminalTabActive = false;
    private boolean mWasSoftKeyboardVisible = false;

    private TerminalView mTerminalView;
    private View mFragmentContainer;
    private ViewPager mExtraKeysPager;
    private BottomNavigationView mBottomNavigation;

    private ViewTreeObserver.OnGlobalLayoutListener mKeyboardListener;

    public TerminalDelegate(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public void onCreate() {
        mTerminalView = mActivity.findViewById(R.id.terminal_view);
        mFragmentContainer = mActivity.findViewById(R.id.fragment_container);
        mExtraKeysPager = mActivity.findViewById(R.id.terminal_toolbar_view_pager);
        mBottomNavigation = mActivity.findViewById(R.id.bottom_navigation);
    }

    public void onTerminalTabActivated() {
        mTerminalTabActive = true;

        // Hide fragment container, show terminal
        if (mFragmentContainer != null) mFragmentContainer.setVisibility(View.GONE);
        if (mTerminalView != null) mTerminalView.setVisibility(View.VISIBLE);

        // Show extra keys if preference says so
        TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        if (mExtraKeysPager != null && prefs != null && prefs.shouldShowTerminalToolbar()) {
            mExtraKeysPager.setVisibility(View.VISIBLE);
        }

        // Request focus and restart cursor blinker
        if (mTerminalView != null) {
            mTerminalView.requestFocus();
            mTerminalView.onScreenUpdated();
        }

        // Restart cursor blinker
        if (mActivity.getTermuxTerminalViewClient() != null) {
            mActivity.getTermuxTerminalViewClient().setTerminalCursorBlinkerState(true);
        }

        // Restore IME state with delay (matches existing pattern from setSoftKeyboardState)
        restoreImeState();

        // Register keyboard visibility listener for dynamic BottomNav hiding
        registerKeyboardListener();
    }

    public void onTerminalTabDeactivated() {
        mTerminalTabActive = false;

        // Save IME state before hiding
        saveImeState();

        // Hide soft keyboard
        if (mTerminalView != null) {
            KeyboardUtils.hideSoftKeyboard(mActivity, mTerminalView);
        }

        // Stop cursor blinker
        if (mActivity.getTermuxTerminalViewClient() != null) {
            mActivity.getTermuxTerminalViewClient().setTerminalCursorBlinkerState(false);
        }

        // Hide terminal views
        if (mTerminalView != null) mTerminalView.setVisibility(View.GONE);
        if (mExtraKeysPager != null) mExtraKeysPager.setVisibility(View.GONE);

        // Unregister keyboard listener
        unregisterKeyboardListener();

        // Show BottomNav and fragment container
        if (mBottomNavigation != null) mBottomNavigation.setVisibility(View.VISIBLE);
        if (mFragmentContainer != null) mFragmentContainer.setVisibility(View.VISIBLE);
    }

    public boolean isTerminalTabActive() {
        return mTerminalTabActive;
    }

    private void saveImeState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mWasSoftKeyboardVisible = KeyboardUtils.isSoftKeyboardVisible(mActivity);
        } else {
            // Fallback: assume visible if terminal has focus
            mWasSoftKeyboardVisible = mTerminalView != null && mTerminalView.hasFocus();
        }
    }

    private void restoreImeState() {
        if (mTerminalView == null) return;

        if (!mWasSoftKeyboardVisible) return;

        // Check if soft keyboard is enabled in preferences
        TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        if (prefs != null && !prefs.isSoftKeyboardEnabled()) return;

        // Show with delay matching existing TermuxTerminalViewClient pattern (300ms)
        mTerminalView.postDelayed(() -> {
            if (mTerminalView != null && mTerminalTabActive) {
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                KeyboardUtils.showSoftKeyboard(mActivity, mTerminalView);
            }
        }, 300);
    }

    private void registerKeyboardListener() {
        if (mKeyboardListener != null) return;

        final View rootView = mActivity.getWindow().getDecorView().getRootView();
        mKeyboardListener = () -> updateBottomNavVisibility();
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(mKeyboardListener);
    }

    private void unregisterKeyboardListener() {
        if (mKeyboardListener == null) return;

        final View rootView = mActivity.getWindow().getDecorView().getRootView();
        rootView.getViewTreeObserver().removeOnGlobalLayoutListener(mKeyboardListener);
        mKeyboardListener = null;
    }

    private void updateBottomNavVisibility() {
        if (!mTerminalTabActive || mBottomNavigation == null) return;

        boolean keyboardVisible;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyboardVisible = KeyboardUtils.isSoftKeyboardVisible(mActivity);
        } else {
            // Fallback: check if root view visible height is significantly less than total
            View rootView = mActivity.getWindow().getDecorView().getRootView();
            int rootViewHeight = rootView.getRootView().getHeight();
            int visibleHeight = rootView.getHeight();
            keyboardVisible = rootViewHeight - visibleHeight > rootViewHeight * 0.15;
        }

        int desiredVisibility = keyboardVisible ? View.GONE : View.VISIBLE;
        if (mBottomNavigation.getVisibility() != desiredVisibility) {
            mBottomNavigation.setVisibility(desiredVisibility);
        }
    }
}
