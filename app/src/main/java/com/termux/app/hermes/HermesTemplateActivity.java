package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.termux.R;

public class HermesTemplateActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(this);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.template_title);
        }
        root.addView(toolbar);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, scrollParams);

        // Header
        TextView header = new TextView(this);
        header.setText(R.string.template_header);
        header.setTextSize(14);
        header.setPadding(0, 0, 0, dp(16));
        content.addView(header);

        // Templates
        addTemplate(content, "code_assistant");
        addTemplate(content, "creative_writer");
        addTemplate(content, "study_buddy");
        addTemplate(content, "email_writer");
        addTemplate(content, "research_assistant");

        setContentView(root);
    }

    private void addTemplate(LinearLayout parent, String templateId) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackgroundColor(0x0F000000);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        // Title row
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView emoji = new TextView(this);
        emoji.setTextSize(24);
        emoji.setPadding(0, 0, dp(8), 0);
        titleRow.addView(emoji);

        TextView title = new TextView(this);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(16);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(titleRow);

        // Description
        TextView desc = new TextView(this);
        desc.setTextSize(13);
        desc.setPadding(0, dp(4), 0, dp(4));
        desc.setLineSpacing(dp(2), 1f);
        card.addView(desc);

        // Settings preview
        TextView settings = new TextView(this);
        settings.setTextSize(12);
        settings.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        settings.setLineSpacing(dp(1), 1f);
        card.addView(settings);

        // Apply button
        Button applyBtn = new Button(this);
        applyBtn.setText(R.string.template_apply);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = Gravity.END;
        applyBtn.setLayoutParams(btnParams);

        TemplateData data = getTemplateData(templateId);
        emoji.setText(data.emoji);
        title.setText(data.title);
        desc.setText(data.description);
        settings.setText(data.settingsPreview);

        applyBtn.setOnClickListener(v -> {
            applyTemplate(data);
            Toast.makeText(this, getString(R.string.template_applied, data.title), Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        card.addView(applyBtn);

        parent.addView(card);
    }

    private void applyTemplate(TemplateData data) {
        HermesConfigManager config = HermesConfigManager.getInstance();
        config.setModelTemperature(data.temperature);
        config.setModelMaxTokens(data.maxTokens);
        config.setSystemPrompt(data.systemPrompt);

        // Set recommended model if available
        String provider = config.getModelProvider();
        String recommendedModel = getRecommendedModel(provider, data);
        if (recommendedModel != null) {
            config.setModelName(recommendedModel);
        }

        HermesConfigManager.restartGatewayIfRunning(this);
    }

    private String getRecommendedModel(String provider, TemplateData data) {
        switch (provider) {
            case "openai":
                return data.needsReasoning ? "o3-mini" : "gpt-4o";
            case "anthropic":
                return "claude-sonnet-4-6";
            case "google":
                return "gemini-2.5-flash";
            case "deepseek":
                return "deepseek-chat";
            case "openrouter":
                return "openai/gpt-4o";
            case "ollama":
                return "llama3";
            default:
                return null;
        }
    }

    private TemplateData getTemplateData(String id) {
        switch (id) {
            case "code_assistant":
                return new TemplateData(
                        getString(R.string.template_code_title),
                        "💻",
                        getString(R.string.template_code_desc),
                        getString(R.string.template_code_settings),
                        getString(R.string.template_code_prompt),
                        0.2f, 4096, false
                );
            case "creative_writer":
                return new TemplateData(
                        getString(R.string.template_creative_title),
                        "✍️",
                        getString(R.string.template_creative_desc),
                        getString(R.string.template_creative_settings),
                        getString(R.string.template_creative_prompt),
                        0.9f, 2048, false
                );
            case "study_buddy":
                return new TemplateData(
                        getString(R.string.template_study_title),
                        "📚",
                        getString(R.string.template_study_desc),
                        getString(R.string.template_study_settings),
                        getString(R.string.template_study_prompt),
                        0.7f, 2048, false
                );
            case "email_writer":
                return new TemplateData(
                        getString(R.string.template_email_title),
                        "📧",
                        getString(R.string.template_email_desc),
                        getString(R.string.template_email_settings),
                        getString(R.string.template_email_prompt),
                        0.5f, 1024, false
                );
            case "research_assistant":
                return new TemplateData(
                        getString(R.string.template_research_title),
                        "🔍",
                        getString(R.string.template_research_desc),
                        getString(R.string.template_research_settings),
                        getString(R.string.template_research_prompt),
                        0.3f, 4096, true
                );
            default:
                return new TemplateData("Unknown", "?", "", "", "", 0.7f, 2048, false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static class TemplateData {
        final String title;
        final String emoji;
        final String description;
        final String settingsPreview;
        final String systemPrompt;
        final float temperature;
        final int maxTokens;
        final boolean needsReasoning;

        TemplateData(String title, String emoji, String description, String settingsPreview,
                      String systemPrompt, float temperature, int maxTokens, boolean needsReasoning) {
            this.title = title;
            this.emoji = emoji;
            this.description = description;
            this.settingsPreview = settingsPreview;
            this.systemPrompt = systemPrompt;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.needsReasoning = needsReasoning;
        }
    }
}
