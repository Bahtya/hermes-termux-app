package com.termux.app.terminal;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.io.File;

public class HermesTabBarController {

    private static final String LOG_TAG = "HermesTabBarController";
    private static final String PREF_ACTIVE_TAB = "hermes_active_tab";
    static final String TAB_BASH = "bash";
    static final String TAB_HERMES = "hermes";

    private static final String HERMES_BIN_PATH =
        TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";

    private final TermuxActivity mActivity;
    private final LinearLayout mTabBar;
    private final MaterialButton mTabBash;
    private final MaterialButton mTabHermes;

    private String mActiveTab = TAB_BASH;
    private TerminalSession mBashSession;
    private TerminalSession mHermesSession;
    private boolean mBashSessionFinished = true;
    private boolean mHermesSessionFinished = true;

    public HermesTabBarController(TermuxActivity activity) {
        mActivity = activity;
        mTabBar = activity.findViewById(R.id.tab_bar);
        mTabBash = activity.findViewById(R.id.tab_bash);
        mTabHermes = activity.findViewById(R.id.tab_hermes);

        mTabBash.setOnClickListener(v -> switchToTab(TAB_BASH));
        mTabHermes.setOnClickListener(v -> switchToTab(TAB_HERMES));

        mActiveTab = loadActiveTab();
        updateTabAppearance();
    }

    public void onServiceConnected() {
        recoverExistingSessions();
        ensureBashSession();
        ensureHermesSession();

        mTabBar.setVisibility(View.VISIBLE);
        switchToTab(mActiveTab);
    }

    private void recoverExistingSessions() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int size = service.getTermuxSessionsSize();
        for (int i = 0; i < size; i++) {
            TermuxSession ts = service.getTermuxSession(i);
            if (ts == null) continue;
            TerminalSession session = ts.getTerminalSession();
            String name = session.mSessionName;

            if (TAB_HERMES.equals(name) && (mHermesSession == null || mHermesSessionFinished)) {
                registerSession(TAB_HERMES, session);
            } else if (mBashSession == null || mBashSessionFinished) {
                // First non-hermes session is the bash session
                registerSession(TAB_BASH, session);
            }
        }
    }

    private void ensureBashSession() {
        if (mBashSession != null && !mBashSessionFinished) return;
        // The bash session is created by the normal Termux flow.
        // If we still don't have one, use the current session as the bash session.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession current = mActivity.getCurrentSession();
        if (current != null && current != mHermesSession) {
            registerSession(TAB_BASH, current);
        } else if (service.getTermuxSessionsSize() > 0) {
            TermuxSession ts = service.getTermuxSession(0);
            if (ts != null && ts.getTerminalSession() != mHermesSession) {
                registerSession(TAB_BASH, ts.getTerminalSession());
            }
        }
    }

    private void ensureHermesSession() {
        if (mHermesSession != null && !mHermesSessionFinished) return;
        createHermesSession();
    }

    private void createHermesSession() {
        File hermesBin = new File(HERMES_BIN_PATH);
        if (hermesBin.canExecute()) {
            mActivity.getTermuxTerminalSessionClient().addHermesSession(
                HERMES_BIN_PATH, null, TermuxConstants.TERMUX_HOME_DIR_PATH, TAB_HERMES);
        } else {
            // Hermes not installed yet — run hermes command which will fail with a helpful message
            mActivity.getTermuxTerminalSessionClient().addHermesSession(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                new String[]{"-c", "echo 'Hermes is being installed... Please wait or switch to Bash tab.'; echo 'Once installed, close this session and switch back to Hermes.'; exec bash"},
                TermuxConstants.TERMUX_HOME_DIR_PATH, TAB_HERMES);
        }
    }

    public void registerSession(String tab, TerminalSession session) {
        if (TAB_BASH.equals(tab)) {
            mBashSession = session;
            mBashSessionFinished = false;
        } else if (TAB_HERMES.equals(tab)) {
            mHermesSession = session;
            mHermesSessionFinished = false;
        }
    }

    public void switchToTab(String tab) {
        mActiveTab = tab;
        saveActiveTab(tab);
        updateTabAppearance();

        TerminalSession target;
        if (TAB_HERMES.equals(tab)) {
            target = mHermesSession;
            if (target == null || mHermesSessionFinished) {
                ensureHermesSession();
                target = mHermesSession;
            }
        } else {
            target = mBashSession;
            if (target == null || mBashSessionFinished) {
                ensureBashSession();
                target = mBashSession;
            }
        }

        if (target != null) {
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(target);
        }
    }

    public String getActiveTab() {
        return mActiveTab;
    }

    public void onSessionFinished(TerminalSession session) {
        if (session == mBashSession) {
            mBashSessionFinished = true;
        } else if (session == mHermesSession) {
            mHermesSessionFinished = true;
        }
    }

    public String getTabForSession(TerminalSession session) {
        if (session == mBashSession) return TAB_BASH;
        if (session == mHermesSession) return TAB_HERMES;
        return null;
    }

    private void updateTabAppearance() {
        boolean isBash = TAB_BASH.equals(mActiveTab);
        mTabBash.setBackgroundColor(isBash ? 0x33FFFFFF : Color.TRANSPARENT);
        mTabHermes.setBackgroundColor(!isBash ? 0x33FFFFFF : Color.TRANSPARENT);
    }

    private void saveActiveTab(String tab) {
        SharedPreferences prefs = mActivity.getSharedPreferences("hermes_tabs", 0);
        prefs.edit().putString(PREF_ACTIVE_TAB, tab).apply();
    }

    private String loadActiveTab() {
        SharedPreferences prefs = mActivity.getSharedPreferences("hermes_tabs", 0);
        return prefs.getString(PREF_ACTIVE_TAB, TAB_BASH);
    }
}
