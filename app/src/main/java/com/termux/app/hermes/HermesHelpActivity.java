package com.termux.app.hermes;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

public class HermesHelpActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.help_title);
        }

        addSection(root, R.string.help_what_is_hermes_title, R.string.help_what_is_hermes);
        addSection(root, R.string.help_quick_start_title, R.string.help_quick_start);
        addSection(root, R.string.help_connecting_im_title, R.string.help_connecting_im);
        addSection(root, R.string.help_faq_title, R.string.help_faq);
        addSection(root, R.string.help_troubleshooting_title, R.string.help_troubleshooting);

        addSpacer(root, dp(16));
        TextView linksTitle = new TextView(this);
        linksTitle.setText(R.string.help_links_title);
        linksTitle.setTextSize(18);
        linksTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        linksTitle.setPadding(0, 0, 0, dp(8));
        root.addView(linksTitle);

        addLink(root, getString(R.string.help_link_github), "https://github.com/nousresearch/hermes-agent");
        addLink(root, getString(R.string.help_link_docs), "https://hermes-agent.nousresearch.com/docs");
        addLink(root, getString(R.string.help_link_issues), "https://github.com/Bahtya/hermes-termux-app/issues");

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void addSection(LinearLayout parent, int titleRes, int bodyRes) {
        addSpacer(parent, dp(12));

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        parent.addView(title);

        TextView body = new TextView(this);
        body.setText(bodyRes);
        body.setTextSize(14);
        body.setLineSpacing(dp(4), 1f);
        body.setTextColor(0xFF333333);
        parent.addView(body);
    }

    private void addLink(LinearLayout parent, String text, String url) {
        TextView link = new TextView(this);
        link.setText("→ " + text);
        link.setTextSize(14);
        link.setTextColor(0xFF1A73E8);
        link.setPadding(0, dp(4), 0, dp(4));
        link.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });
        parent.addView(link);
    }

    private void addSpacer(LinearLayout parent, int heightPx) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        parent.addView(spacer);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
