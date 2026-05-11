package com.termux.app.hermes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.view.Gravity;

import com.termux.R;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

public class HermesTutorialOverlay {

    private static final String PREF_TUTORIAL_DONE = "hermes_tutorial_completed";
    private static final String PREF_TUTORIAL_STEP = "hermes_tutorial_step";

    private static final String[][] TUTORIAL_STEPS = {
        {"Welcome to Hermes!", "Hermes connects AI assistants to messaging platforms. Let us show you around."},
        {"Start Gateway", "Tap the Gateway tab to start/stop the Hermes gateway service. This is the core that connects everything."},
        {"Configure LLM", "Go to the LLM tab to set up your AI provider (OpenAI, Anthropic, etc.) and API key."},
        {"Connect IM", "Use the IM tab to connect Feishu, Telegram, or Discord so your AI can chat on those platforms."},
        {"You are all set!", "Hermes is now ready. Start the gateway, and your AI assistant will begin responding on your connected platforms."}
    };

    public static boolean isTutorialDone(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_TUTORIAL_DONE, false);
    }

    public static void markTutorialDone(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(PREF_TUTORIAL_DONE, true).apply();
    }

    public static void resetTutorial(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(PREF_TUTORIAL_DONE)
                .remove(PREF_TUTORIAL_STEP)
                .apply();
    }

    public static int getSavedStep(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PREF_TUTORIAL_STEP, 0);
    }

    public static void saveStep(Context context, int step) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putInt(PREF_TUTORIAL_STEP, step).apply();
    }

    public static void show(Context context, ViewGroup root, int step, Runnable onComplete) {
        if (step >= TUTORIAL_STEPS.length) {
            markTutorialDone(context);
            if (onComplete != null) onComplete.run();
            return;
        }

        // Remove any existing overlay
        View existing = root.findViewWithTag("hermes_tutorial");
        if (existing != null) root.removeView(existing);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("hermes_tutorial");
        overlay.setBackgroundColor(0xB3000000); // 70% black

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        overlay.setLayoutParams(overlayParams);

        // Tooltip card
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 16));
        card.setElevation(dp(context, 8));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(context, 320), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        card.setLayoutParams(cardParams);

        // Step indicator
        TextView stepText = new TextView(context);
        stepText.setText((step + 1) + " / " + TUTORIAL_STEPS.length);
        stepText.setTextSize(12);
        stepText.setTextColor(0xFF888888);
        stepText.setGravity(Gravity.CENTER);
        card.addView(stepText);

        // Title
        TextView title = new TextView(context);
        title.setText(TUTORIAL_STEPS[step][0]);
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(context, 8), 0, dp(context, 4));
        card.addView(title);

        // Description
        TextView desc = new TextView(context);
        desc.setText(TUTORIAL_STEPS[step][1]);
        desc.setTextSize(14);
        desc.setTextColor(0xFF444444);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(context, 4), 0, dp(context, 16));
        card.addView(desc);

        // Button bar
        LinearLayout buttonBar = new LinearLayout(context);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setGravity(Gravity.CENTER);

        if (step > 0) {
            Button backBtn = new Button(context);
            backBtn.setText(R.string.tutorial_back);
            backBtn.setOnClickListener(v -> {
                root.removeView(overlay);
                saveStep(context, step - 1);
                show(context, root, step - 1, onComplete);
            });
            buttonBar.addView(backBtn);
        }

        Button nextBtn = new Button(context);
        nextBtn.setText(step == TUTORIAL_STEPS.length - 1 ? context.getString(R.string.tutorial_done) : context.getString(R.string.tutorial_next));
        nextBtn.setOnClickListener(v -> {
            root.removeView(overlay);
            if (step == TUTORIAL_STEPS.length - 1) {
                markTutorialDone(context);
                if (onComplete != null) onComplete.run();
            } else {
                saveStep(context, step + 1);
                show(context, root, step + 1, onComplete);
            }
        });
        buttonBar.addView(nextBtn);

        Button skipBtn = new Button(context);
        skipBtn.setText(R.string.tutorial_skip);
        skipBtn.setTextColor(0xFF888888);
        skipBtn.setOnClickListener(v -> {
            root.removeView(overlay);
            markTutorialDone(context);
            if (onComplete != null) onComplete.run();
        });
        buttonBar.addView(skipBtn);

        card.addView(buttonBar);
        overlay.addView(card);

        overlay.setOnClickListener(v -> {
            // Dismiss on tap outside
        });

        overlay.setAlpha(0f);
        root.addView(overlay);
        overlay.animate().alpha(1f).setDuration(200).start();

        saveStep(context, step);
    }

    public static void showIfNeeded(Context context, ViewGroup root, Runnable onComplete) {
        if (!isTutorialDone(context)) {
            show(context, root, getSavedStep(context), onComplete);
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
