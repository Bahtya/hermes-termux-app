package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.hermes.HermesTutorialOverlay;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.List;

public class HermesConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_config);

        // Show tutorial for first-time users
        if (!HermesTutorialOverlay.isTutorialDone(this)) {
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.post(() -> HermesTutorialOverlay.showIfNeeded(this,
                        (ViewGroup) rootView, null));
            }
        }
        setSupportActionBar(findViewById(R.id.hermes_config_toolbar));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.hermes_config_title);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.hermes_config_content, new HermesConfigFragment())
                    .commit();
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

    public static class HermesConfigFragment extends PreferenceFragmentCompat {

        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // --- Dashboard: Gateway status ---
            Preference dashGateway = findPreference("hermes_dashboard_gateway");
            if (dashGateway != null) {
                HermesGatewayStatus.checkAsync((status, detail) -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        switch (status) {
                            case RUNNING:
                                dashGateway.setSummary(getString(R.string.dashboard_gateway_running));
                                break;
                            case NOT_INSTALLED:
                                dashGateway.setSummary(getString(R.string.dashboard_gateway_not_installed));
                                break;
                            default:
                                dashGateway.setSummary(getString(R.string.dashboard_gateway_stopped));
                                break;
                        }
                    });
                });
            }

            // --- Dashboard: LLM status ---
            Preference dashLlm = findPreference("hermes_dashboard_llm");
            if (dashLlm != null) {
                String provider = mConfigManager.getModelProvider();
                String apiKey = mConfigManager.getApiKey(provider);
                if (apiKey != null && !apiKey.isEmpty()) {
                    dashLlm.setSummary(getString(R.string.dashboard_llm_configured,
                            provider, mConfigManager.getModelName()));
                } else {
                    dashLlm.setSummary(getString(R.string.dashboard_llm_not_configured));
                }
            }

            // --- Dashboard: IM status ---
            Preference dashIm = findPreference("hermes_dashboard_im");
            if (dashIm != null) {
                java.util.List<String> platforms = new java.util.ArrayList<>();
                if (mConfigManager.isFeishuConfigured()) platforms.add("Feishu");
                if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) platforms.add("Telegram");
                if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) platforms.add("Discord");
                if (!mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty()) platforms.add("WhatsApp");
                if (platforms.isEmpty()) {
                    dashIm.setSummary(getString(R.string.dashboard_im_none));
                } else {
                    dashIm.setSummary(getString(R.string.dashboard_im_list,
                            android.text.TextUtils.join(", ", platforms)));
                }
            }

            // --- Health check summary ---
            updateHealthCheck();

            // --- Config validation ---
            updateValidationDisplay();

            // --- Usage stats ---
            updateUsageStats();

            // Show gateway status
            Preference gatewayPref = findPreference("hermes_gateway_control");
            if (gatewayPref != null) {
                HermesGatewayStatus.checkAsync((status, detail) -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        String summary;
                        switch (status) {
                            case RUNNING:
                                summary = getString(R.string.gateway_status_running);
                                break;
                            case NOT_INSTALLED:
                                summary = getString(R.string.gateway_status_not_installed);
                                break;
                            default:
                                summary = getString(R.string.gateway_status_stopped);
                                break;
                        }
                        gatewayPref.setSummary(summary);
                    });
                });
            }

            // Auto-start gateway toggle
            SwitchPreferenceCompat autoStartPref = findPreference("hermes_auto_start_gateway");
            if (autoStartPref != null) {
                autoStartPref.setOnPreferenceChangeListener((p, newVal) -> {
                    boolean enabled = (Boolean) newVal;
                    HermesGatewayService.setAutoStartEnabled(requireContext(), enabled);
                    if (enabled) {
                        requireContext().startService(
                                new Intent(requireContext(), HermesGatewayService.class)
                                        .setAction(HermesGatewayService.ACTION_START));
                    }
                    return true;
                });
            }

            // Command timeout
            Preference cmdTimeoutPref = findPreference("hermes_command_timeout");
            if (cmdTimeoutPref != null) {
                cmdTimeoutPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelCommandTimeout(Integer.parseInt((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            // Gateway auto-restart toggle
            SwitchPreferenceCompat autoRestartPref = findPreference("hermes_gateway_auto_restart");
            if (autoRestartPref != null) {
                autoRestartPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_AUTO_RESTART", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Max restart attempts
            Preference maxRestartsPref = findPreference("hermes_gateway_max_restarts");
            if (maxRestartsPref != null) {
                maxRestartsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        int val = Integer.parseInt((String) newVal);
                        mConfigManager.setEnvVar("GATEWAY_MAX_RESTARTS", String.valueOf(val));
                    } catch (NumberFormatException ignored) {}
                    return true;
                });
            }

            // Restart delay
            Preference restartDelayPref = findPreference("hermes_restart_delay");
            if (restartDelayPref != null) {
                restartDelayPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setEnvVar("GATEWAY_RESTART_DELAY", (String) newVal);
                    } catch (Exception ignored) {}
                    return true;
                });
            }

            // Notification sound toggle
            SwitchPreferenceCompat notifSoundPref = findPreference("hermes_gateway_notif_sound");
            if (notifSoundPref != null) {
                notifSoundPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_NOTIF_SOUND", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Notification vibrate toggle
            SwitchPreferenceCompat notifVibratePref = findPreference("hermes_gateway_notif_vibrate");
            if (notifVibratePref != null) {
                notifVibratePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_NOTIF_VIBRATE", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Show LLM config status
            Preference llmPref = findPreference("hermes_llm_config");
            if (llmPref != null) {
                String provider = mConfigManager.getModelProvider();
                String apiKey = mConfigManager.getApiKey(provider);
                boolean hasKey = apiKey != null && !apiKey.isEmpty();
                llmPref.setSummary(hasKey
                        ? getString(R.string.llm_configured, provider)
                        : getString(R.string.llm_not_configured));
            }

            // Show Feishu status
            Preference feishuPref = findPreference("hermes_feishu_setup");
            if (feishuPref != null) {
                boolean configured = mConfigManager.isFeishuConfigured();
                feishuPref.setSummary(configured
                        ? getString(R.string.feishu_configured_detail, maskCredential(mConfigManager.getFeishuAppId(), 8))
                        : getString(R.string.feishu_not_configured));
            }

            // Show Telegram status
            Preference telegramPref = findPreference("hermes_telegram_setup");
            if (telegramPref != null) {
                boolean configured = !mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty();
                telegramPref.setSummary(configured
                        ? getString(R.string.telegram_configured_detail, maskCredential(mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN"), 8))
                        : getString(R.string.telegram_not_configured));
            }

            // Show Discord status
            Preference discordPref = findPreference("hermes_discord_setup");
            if (discordPref != null) {
                boolean configured = !mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty();
                discordPref.setSummary(configured
                        ? getString(R.string.discord_configured_detail, maskCredential(mConfigManager.getEnvVar("DISCORD_BOT_TOKEN"), 8))
                        : getString(R.string.discord_not_configured));
            }

            // Show WhatsApp status
            Preference whatsappPref = findPreference("hermes_whatsapp_setup");
            if (whatsappPref != null) {
                boolean configured = !mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty();
                whatsappPref.setSummary(configured
                        ? getString(R.string.whatsapp_configured)
                        : getString(R.string.whatsapp_not_configured));
            }

        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if (key == null) return super.onPreferenceTreeClick(preference);

            switch (key) {
                case "hermes_llm_config":
                    showFragment(new LlmConfigFragment());
                    return true;
                case "hermes_feishu_setup":
                    startActivity(new Intent(requireContext(), FeishuSetupActivity.class));
                    return true;
                case "hermes_telegram_setup":
                    Intent tgIntent = new Intent(requireContext(), ImSetupActivity.class);
                    tgIntent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_TELEGRAM);
                    startActivity(tgIntent);
                    return true;
                case "hermes_discord_setup":
                    Intent dcIntent = new Intent(requireContext(), ImSetupActivity.class);
                    dcIntent.putExtra(ImSetupActivity.EXTRA_PLATFORM, ImSetupActivity.PLATFORM_DISCORD);
                    startActivity(dcIntent);
                    return true;
                case "hermes_whatsapp_setup":
                    startActivity(new Intent(requireContext(), WhatsAppSetupActivity.class));
                    return true;
                case "hermes_gateway_control":
                    showFragment(new GatewayControlFragment());
                    return true;
                case "hermes_check_update":
                    checkForUpdate(preference);
                    return true;
                case "hermes_gateway_log":
                    startActivity(new Intent(requireContext(), GatewayLogActivity.class));
                    return true;
                case "hermes_gateway_diagnostic":
                    startActivity(new Intent(requireContext(), HermesDiagnosticActivity.class));
                    return true;
                case "hermes_agent_settings":
                    showFragment(new AgentSettingsFragment());
                    return true;
                case "hermes_mcp_settings":
                    showMcpServersDialog();
                    return true;
                case "hermes_conversation_history":
                    startActivity(new Intent(requireContext(), HermesConversationActivity.class));
                    return true;
                case "hermes_templates":
                    startActivity(new Intent(requireContext(), HermesTemplateActivity.class));
                    return true;
                case "hermes_reset_config":
                    showResetConfirmDialog();
                    return true;
                case "hermes_export_config":
                    exportConfig();
                    return true;
                case "hermes_import_config":
                    showImportConfirmDialog();
                    return true;
                case "hermes_help_faq":
                    showFaqDialog();
                    return true;
                case "hermes_profile_save":
                    showSaveProfileDialog();
                    return true;
                case "hermes_profile_switch":
                    showSwitchProfileDialog();
                    return true;
                case "hermes_profile_delete":
                    showDeleteProfileDialog();
                    return true;
                case "tutorial_reset":
                    HermesTutorialOverlay.resetTutorial(requireContext());
                    Toast.makeText(requireContext(), R.string.tutorial_reset_summary, Toast.LENGTH_SHORT).show();
                    return true;
                case "validation_api_key":
                    showFragment(new LlmConfigFragment());
                    return true;
                case "validation_gateway":
                    showFragment(new GatewayControlFragment());
                    return true;
                case "validation_im":
                    startActivity(new Intent(requireContext(), FeishuSetupActivity.class));
                    return true;
                case "validation_system_prompt":
                    showFragment(new LlmConfigFragment());
                    return true;
                case "hermes_health_check":
                    showFragment(new LlmConfigFragment());
                    return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private String maskCredential(String value, int visibleChars) {
            if (value == null || value.isEmpty()) return "";
            if (value.length() <= visibleChars) return value;
            return value.substring(0, visibleChars) + "...";
        }

        private void showFragment(Fragment fragment) {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.hermes_config_content, fragment)
                    .addToBackStack(null)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .commit();
        }

        private void checkForUpdate(Preference updatePref) {
            if (updatePref != null) {
                updatePref.setSummary(getString(R.string.hermes_update_checking));
            }
            new Thread(() -> {
                String[] result = fetchVersionInfo();
                requireActivity().runOnUiThread(() -> {
                    if (result == null) {
                        if (updatePref != null) {
                            updatePref.setSummary(getString(R.string.hermes_update_failed));
                        }
                    } else {
                        String current = result[0];
                        String latest = result[1];
                        if (updatePref != null) {
                            if (latest != null && !latest.equals(current)) {
                                updatePref.setSummary(getString(R.string.gateway_update_available, latest, current));
                                updatePref.setOnPreferenceClickListener(p -> {
                                    performUpdate(updatePref, latest);
                                    return true;
                                });
                            } else {
                                updatePref.setSummary(getString(R.string.gateway_update_up_to_date, current));
                                updatePref.setOnPreferenceClickListener(null);
                            }
                        }
                    }
                });
            }).start();
        }

        private void performUpdate(Preference updatePref, String targetVersion) {
            if (updatePref != null) {
                updatePref.setSummary(getString(R.string.gateway_update_downloading));
            }
            new Thread(() -> {
                try {
                    String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                    ProcessBuilder pb = new ProcessBuilder(binPath + "/bash", "-c",
                            "pip install --upgrade hermes-agent 2>&1 | tail -5");
                    pb.environment().put("PATH", binPath + ":/system/bin");
                    pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    p.waitFor();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    boolean success = p.exitValue() == 0;
                    if (getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        if (success) {
                            if (updatePref != null) {
                                updatePref.setSummary(getString(R.string.gateway_update_installed, targetVersion));
                            }
                            Toast.makeText(requireContext(), getString(R.string.gateway_update_installed, targetVersion), Toast.LENGTH_LONG).show();
                        } else {
                            if (updatePref != null) {
                                updatePref.setSummary(getString(R.string.gateway_update_install_failed));
                            }
                            Toast.makeText(requireContext(), R.string.gateway_update_install_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    if (getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        if (updatePref != null) {
                            updatePref.setSummary(getString(R.string.gateway_update_install_failed));
                        }
                    });
                }
            }).start();
        }

        private String[] fetchVersionInfo() {
            try {
                String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                ProcessBuilder pb = new ProcessBuilder(binPath + "/bash", "-c",
                        "hermes --version 2>/dev/null | head -1");
                pb.environment().put("PATH", binPath + ":/system/bin");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String current = reader.readLine();
                if (current != null) current = current.trim();

                ProcessBuilder pb2 = new ProcessBuilder(binPath + "/bash", "-c",
                        "pip show hermes-agent 2>/dev/null | grep Version | head -1 | cut -d' ' -f2");
                pb2.environment().put("PATH", binPath + ":/system/bin");
                pb2.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.waitFor();
                java.io.BufferedReader reader2 = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p2.getInputStream()));
                String pipVersion = reader2.readLine();
                if (pipVersion != null) pipVersion = pipVersion.trim();

                if (current == null || current.isEmpty()) current = "unknown";
                String installedVersion = pipVersion != null ? pipVersion : current;

                // Fetch latest version from GitHub releases
                String latestVersion = fetchLatestGithubVersion(binPath);

                return new String[]{installedVersion, latestVersion};
            } catch (Exception e) {
                return null;
            }
        }

        private String fetchLatestGithubVersion(String binPath) {
            try {
                ProcessBuilder pb = new ProcessBuilder(binPath + "/bash", "-c",
                        "pip index versions hermes-agent 2>/dev/null | head -1 | grep -oP '\\(.*?\\)' | head -1 | tr -d '()'");
                pb.environment().put("PATH", binPath + ":/system/bin");
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String version = reader.readLine();
                if (version != null) {
                    version = version.trim();
                    if (!version.isEmpty()) return version;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private void showWhatsappSetupDialog() {
            ScrollView scrollView = new ScrollView(requireContext());
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            layout.setPadding(pad, pad, pad, pad);

            // Title
            TextView title = new TextView(requireContext());
            title.setText(R.string.whatsapp_welcome_title);
            title.setTextSize(18);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, 0, 0, dp(8));
            layout.addView(title);

            // Instructions
            TextView steps = new TextView(requireContext());
            steps.setText(R.string.whatsapp_get_token_steps);
            steps.setTextSize(13);
            steps.setPadding(0, 0, 0, dp(16));
            layout.addView(steps);

            // Phone Number ID
            TextView phoneLabel = new TextView(requireContext());
            phoneLabel.setText(R.string.whatsapp_phone_id_title);
            phoneLabel.setTextSize(14);
            phoneLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            layout.addView(phoneLabel);
            EditText phoneInput = new EditText(requireContext());
            phoneInput.setHint(R.string.whatsapp_phone_id_hint);
            phoneInput.setSingleLine(true);
            String existingPhone = mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID");
            if (!existingPhone.isEmpty()) phoneInput.setText(existingPhone);
            layout.addView(phoneInput);

            // Access Token
            TextView tokenLabel = new TextView(requireContext());
            tokenLabel.setText(R.string.whatsapp_token_title);
            tokenLabel.setTextSize(14);
            tokenLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            tokenLabel.setPadding(0, dp(8), 0, 0);
            layout.addView(tokenLabel);
            EditText tokenInput = new EditText(requireContext());
            tokenInput.setHint(R.string.whatsapp_token_hint);
            tokenInput.setSingleLine(false);
            String existingToken = mConfigManager.getEnvVar("WHATSAPP_ACCESS_TOKEN");
            if (!existingToken.isEmpty()) tokenInput.setText(existingToken);
            layout.addView(tokenInput);

            // Business Account ID
            TextView acctLabel = new TextView(requireContext());
            acctLabel.setText(R.string.whatsapp_account_id_title);
            acctLabel.setTextSize(14);
            acctLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            acctLabel.setPadding(0, dp(8), 0, 0);
            layout.addView(acctLabel);
            EditText acctInput = new EditText(requireContext());
            acctInput.setHint(R.string.whatsapp_account_id_hint);
            acctInput.setSingleLine(true);
            String existingAcct = mConfigManager.getEnvVar("WHATSAPP_BUSINESS_ACCOUNT_ID");
            if (!existingAcct.isEmpty()) acctInput.setText(existingAcct);
            layout.addView(acctInput);

            // Webhook Verify Token
            TextView verifyLabel = new TextView(requireContext());
            verifyLabel.setText(R.string.whatsapp_verify_token_title);
            verifyLabel.setTextSize(14);
            verifyLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            verifyLabel.setPadding(0, dp(8), 0, 0);
            layout.addView(verifyLabel);
            EditText verifyInput = new EditText(requireContext());
            verifyInput.setHint(R.string.whatsapp_verify_token_hint);
            verifyInput.setSingleLine(true);
            String existingVerify = mConfigManager.getEnvVar("WHATSAPP_VERIFY_TOKEN");
            if (!existingVerify.isEmpty()) verifyInput.setText(existingVerify);
            layout.addView(verifyInput);

            scrollView.addView(layout);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.whatsapp_setup_title)
                    .setView(scrollView)
                    .setPositiveButton(R.string.feishu_finish, (d, w) -> {
                        String phone = phoneInput.getText().toString().trim();
                        String token = tokenInput.getText().toString().trim();
                        String acct = acctInput.getText().toString().trim();
                        String verify = verifyInput.getText().toString().trim();
                        mConfigManager.setEnvVar("WHATSAPP_PHONE_NUMBER_ID", phone);
                        mConfigManager.setEnvVar("WHATSAPP_ACCESS_TOKEN", token);
                        mConfigManager.setEnvVar("WHATSAPP_BUSINESS_ACCOUNT_ID", acct);
                        mConfigManager.setEnvVar("WHATSAPP_VERIFY_TOKEN", verify);
                        HermesConfigManager.restartGatewayIfRunning(requireContext());
                        Toast.makeText(requireContext(), R.string.whatsapp_configured, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void showMcpServersDialog() {
            ScrollView scrollView = new ScrollView(requireContext());
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            layout.setPadding(pad, pad, pad, pad);

            java.util.Map<String, String> servers = mConfigManager.getMcpServers();
            if (servers.isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setText(R.string.mcp_no_servers);
                empty.setPadding(0, dp(8), 0, dp(8));
                layout.addView(empty);
            } else {
                for (java.util.Map.Entry<String, String> entry : servers.entrySet()) {
                    LinearLayout row = new LinearLayout(requireContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(0, dp(4), 0, dp(4));

                    TextView label = new TextView(requireContext());
                    label.setText(entry.getKey() + "\n" + entry.getValue());
                    label.setTextSize(13);
                    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    label.setLayoutParams(labelParams);
                    row.addView(label);

                    TextView removeBtn = new TextView(requireContext());
                    removeBtn.setText(R.string.mcp_server_remove);
                    removeBtn.setTextColor(0xFFD32F2F);
                    removeBtn.setTextSize(13);
                    removeBtn.setPadding(dp(12), 0, 0, 0);
                    String serverName = entry.getKey();
                    removeBtn.setOnClickListener(v -> {
                        mConfigManager.removeMcpServer(serverName);
                        Toast.makeText(requireContext(), R.string.mcp_server_removed, Toast.LENGTH_SHORT).show();
                        // Refresh dialog
                    });
                    row.addView(removeBtn);
                    layout.addView(row);
                }
            }

            com.google.android.material.button.MaterialButton addBtn =
                    new com.google.android.material.button.MaterialButton(requireContext());
            addBtn.setText(R.string.mcp_add_server);
            addBtn.setAllCaps(false);
            addBtn.setCornerRadius(dp(20));
            LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            addParams.topMargin = dp(16);
            addParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            addBtn.setLayoutParams(addParams);
            addBtn.setOnClickListener(v -> showAddMcpServerDialog());
            layout.addView(addBtn);

            scrollView.addView(layout);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mcp_settings_title)
                    .setView(scrollView)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }

        private void showAddMcpServerDialog() {
            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            layout.setPadding(pad, pad, pad, pad);

            EditText nameInput = new EditText(requireContext());
            nameInput.setHint(R.string.mcp_server_name);
            nameInput.setSingleLine(true);
            layout.addView(nameInput);

            Spinner typeSpinner = new Spinner(requireContext());
            ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    getResources().getStringArray(R.array.mcp_server_type_names));
            typeSpinner.setAdapter(typeAdapter);
            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            spinnerParams.topMargin = dp(8);
            typeSpinner.setLayoutParams(spinnerParams);
            layout.addView(typeSpinner);

            EditText commandInput = new EditText(requireContext());
            commandInput.setHint(R.string.mcp_server_command_hint);
            commandInput.setSingleLine(true);
            LinearLayout.LayoutParams cmdParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cmdParams.topMargin = dp(8);
            commandInput.setLayoutParams(cmdParams);
            layout.addView(commandInput);

            typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    String[] values = getResources().getStringArray(R.array.mcp_server_type_values);
                    if ("url".equals(values[pos])) {
                        commandInput.setHint(R.string.mcp_server_url_hint);
                    } else {
                        commandInput.setHint(R.string.mcp_server_command_hint);
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mcp_add_server)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String name = nameInput.getText().toString().trim();
                        String cmd = commandInput.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.mcp_server_save_error, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] values = getResources().getStringArray(R.array.mcp_server_type_values);
                        String type = values[typeSpinner.getSelectedItemPosition()];
                        String configValue = type.equals("url") ? "url:" + cmd : cmd;
                        mConfigManager.addMcpServer(name, configValue);
                        Toast.makeText(requireContext(), R.string.mcp_server_added, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density);
        }

        private void showFaqDialog() {
            ScrollView scrollView = new ScrollView(requireContext());
            TextView textView = new TextView(requireContext());
            int padding = (int) (24 * getResources().getDisplayMetrics().density);
            textView.setPadding(padding, padding, padding, 0);
            textView.setText(getString(R.string.hermes_faq_content));
            scrollView.addView(textView);

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hermes_help_faq_title)
                    .setView(scrollView)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.hermes_faq_copy_debug, (d, which) -> copyDebugInfo())
                    .show();
        }

        private void copyDebugInfo() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Hermes Debug Info ===\n\n");

            // Provider & Model
            String provider = mConfigManager.getModelProvider();
            String model = mConfigManager.getModelName();
            sb.append("Provider: ").append(provider).append("\n");
            sb.append("Model: ").append(model).append("\n");

            // API Key status (masked)
            String apiKey = mConfigManager.getApiKey(provider);
            if (apiKey != null && !apiKey.isEmpty()) {
                String masked;
                if (apiKey.length() < 8) {
                    masked = "****";
                } else {
                    masked = apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
                }
                sb.append("API Key: ").append(masked).append("\n");
            } else {
                sb.append("API Key: (not set)\n");
            }

            // IM status
            sb.append("\nMessaging:\n");
            if (mConfigManager.isFeishuConfigured()) {
                sb.append("  Feishu: Configured\n");
            } else {
                sb.append("  Feishu: Not configured\n");
            }
            if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) {
                sb.append("  Telegram: Configured\n");
            } else {
                sb.append("  Telegram: Not configured\n");
            }
            if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) {
                sb.append("  Discord: Configured\n");
            } else {
                sb.append("  Discord: Not configured\n");
            }

            // Gateway status
            sb.append("\nGateway: ");
            HermesGatewayStatus.checkAsync((status, detail) -> {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    String statusStr;
                    switch (status) {
                        case RUNNING:
                            statusStr = "Running";
                            break;
                        case NOT_INSTALLED:
                            statusStr = "Not installed";
                            break;
                        default:
                            statusStr = "Stopped";
                            break;
                    }
                    sb.append(statusStr).append("\n");

                    ClipboardManager clipboard = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Hermes Debug Info", sb.toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(requireContext(), R.string.hermes_faq_debug_copied,
                            Toast.LENGTH_SHORT).show();
                });
            });
        }

        private void showResetConfirmDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hermes_reset_confirm_title)
                    .setMessage(R.string.hermes_reset_confirm_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        mConfigManager.resetToDefaults();
                        Toast.makeText(requireContext(), R.string.hermes_reset_done, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void exportConfig() {
            new Thread(() -> {
                try {
                    String yaml = readFile(HermesConfigManager.CONFIG_YAML_PATH);
                    String env = readFile(HermesConfigManager.ENV_FILE_PATH);
                    String json = "{\"config_yaml\":" + escapeJson(yaml)
                            + ",\"env\":" + escapeJson(env)
                            + ",\"export_time\":\"" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "\"}";

                    String exportPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/hermes-config-backup.json";
                    writeStringToFile(exportPath, json);
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), getString(R.string.hermes_export_success) + "\n" + exportPath, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), getString(R.string.hermes_export_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        private void showImportConfirmDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hermes_import_confirm_title)
                    .setMessage(R.string.hermes_import_confirm_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> importConfig())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void importConfig() {
            new Thread(() -> {
                try {
                    String importPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/hermes-config-backup.json";
                    String json = readFile(importPath);
                    if (json == null || json.isEmpty()) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), getString(R.string.hermes_import_failed, "Backup file not found"), Toast.LENGTH_LONG).show());
                        return;
                    }

                    String yaml = extractJsonField(json, "config_yaml");
                    String env = extractJsonField(json, "env");

                    if (yaml != null) writeStringToFile(HermesConfigManager.CONFIG_YAML_PATH, yaml);
                    if (env != null) writeStringToFile(HermesConfigManager.ENV_FILE_PATH, env);

                    HermesConfigManager.reinitialize();
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), R.string.hermes_import_success, Toast.LENGTH_SHORT).show();
                        requireActivity().recreate();
                    });
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), getString(R.string.hermes_import_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        private String readFile(String path) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(path))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }

        private void writeStringToFile(String path, String content) throws Exception {
            try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(path))) {
                writer.write(content);
            }
        }

        private String escapeJson(String value) {
            if (value == null) return "null";
            return "\"" + value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t")
                    + "\"";
        }

        private String extractJsonField(String json, String field) {
            String key = "\"" + field + "\":\"";
            int start = json.indexOf(key);
            if (start < 0) return null;
            start += key.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == 'n') { sb.append('\n'); i++; continue; }
                    if (next == 't') { sb.append('\t'); i++; continue; }
                    if (next == '"') { sb.append('"'); i++; continue; }
                    if (next == '\\') { sb.append('\\'); i++; continue; }
                } else if (c == '"') {
                    return sb.toString();
                }
                sb.append(c);
            }
            return sb.toString();
        }

        private void updateProfileDisplay() {
            Preference profilePref = findPreference("hermes_profile_current");
            if (profilePref != null) {
                String name = mConfigManager.getActiveProfileName(requireContext());
                String provider = mConfigManager.getModelProvider();
                String model = mConfigManager.getModelName();
                if ("Default".equals(name)) {
                    profilePref.setSummary(getString(R.string.profile_active_default));
                } else {
                    profilePref.setSummary(getString(R.string.profile_active_format, name, provider, model));
                }
            }
        }

        private void updateHealthCheck() {
            Preference healthPref = findPreference("hermes_health_check");
            if (healthPref == null) return;

            java.util.List<String> issues = new java.util.ArrayList<>();
            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            String model = mConfigManager.getModelName();

            if (apiKey == null || apiKey.isEmpty()) {
                issues.add("api_key");
            }
            if (model == null || model.isEmpty()) {
                issues.add("model");
            }

            int imCount = 0;
            if (mConfigManager.isFeishuConfigured()) imCount++;
            if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) imCount++;
            if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) imCount++;
            if (!mConfigManager.getEnvVar("WHATSAPP_PHONE_NUMBER_ID").isEmpty()) imCount++;
            if (imCount == 0) {
                issues.add("im");
            }

            if (issues.isEmpty()) {
                healthPref.setSummary(getString(R.string.health_check_ready));
            } else {
                healthPref.setSummary(getString(R.string.health_check_issues, issues.size()));
            }
        }

        private void updateValidationDisplay() {
            // API key validation
            Preference apiKeyVal = findPreference("validation_api_key");
            if (apiKeyVal != null) {
                String provider = mConfigManager.getModelProvider();
                String apiKey = mConfigManager.getApiKey(provider);
                if (apiKey != null && !apiKey.isEmpty()) {
                    apiKeyVal.setSummary(getString(R.string.validation_api_key_ok, provider));
                } else {
                    apiKeyVal.setSummary(getString(R.string.validation_api_key_missing));
                }
            }

            // Gateway validation
            Preference gatewayVal = findPreference("validation_gateway");
            if (gatewayVal != null) {
                HermesGatewayStatus.checkAsync((status, detail) -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        switch (status) {
                            case RUNNING:
                                String uptime = HermesGatewayService.getFormattedUptime();
                                gatewayVal.setSummary(getString(R.string.validation_gateway_running, uptime));
                                break;
                            case NOT_INSTALLED:
                                gatewayVal.setSummary(getString(R.string.validation_gateway_not_installed));
                                break;
                            default:
                                gatewayVal.setSummary(getString(R.string.validation_gateway_stopped));
                                break;
                        }
                    });
                });
            }

            // IM validation
            Preference imVal = findPreference("validation_im");
            if (imVal != null) {
                int imCount = 0;
                if (mConfigManager.isFeishuConfigured()) imCount++;
                if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) imCount++;
                if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) imCount++;
                if (imCount > 0) {
                    imVal.setSummary(getString(R.string.validation_im_ok, String.valueOf(imCount)));
                } else {
                    imVal.setSummary(getString(R.string.validation_im_missing));
                }
            }

            // System prompt validation
            Preference promptVal = findPreference("validation_system_prompt");
            if (promptVal != null) {
                String prompt = mConfigManager.getSystemPrompt();
                if (prompt != null && !prompt.isEmpty()) {
                    promptVal.setSummary(getString(R.string.validation_system_prompt_ok, prompt.length()));
                } else {
                    promptVal.setSummary(getString(R.string.validation_system_prompt_missing));
                }
            }

            // Error recovery
            updateErrorRecovery();
        }

        private void updateErrorRecovery() {
            Preference errorPref = findPreference("error_recovery_fix");
            if (errorPref == null) return;

            // Collect all issues
            List<String> issues = new java.util.ArrayList<>();

            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            if (apiKey == null || apiKey.isEmpty()) {
                issues.add("no_api_key");
            }

            int imCount = 0;
            if (mConfigManager.isFeishuConfigured()) imCount++;
            if (!mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) imCount++;
            if (!mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) imCount++;
            if (imCount == 0) {
                issues.add("no_im");
            }

            String prompt = mConfigManager.getSystemPrompt();
            if (prompt == null || prompt.isEmpty()) {
                issues.add("no_prompt");
            }

            // Check gateway status (async)
            HermesGatewayStatus.checkAsync((status, detail) -> {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    List<String> allIssues = new java.util.ArrayList<>(issues);
                    if (status == HermesGatewayStatus.Status.NOT_INSTALLED) {
                        allIssues.add(0, "not_installed");
                    } else if (status != HermesGatewayStatus.Status.RUNNING) {
                        allIssues.add("gateway_down");
                    }

                    if (allIssues.isEmpty()) {
                        errorPref.setSummary(getString(R.string.error_recovery_all_ok));
                        errorPref.setOnPreferenceClickListener(null);
                    } else if (allIssues.size() == 1) {
                        String issue = allIssues.get(0);
                        errorPref.setSummary(getErrorSummary(issue));
                        errorPref.setOnPreferenceClickListener(p -> {
                            fixError(issue);
                            return true;
                        });
                    } else {
                        errorPref.setSummary(getString(R.string.error_recovery_multiple_issues));
                        errorPref.setOnPreferenceClickListener(p -> {
                            fixError(allIssues.get(0));
                            return true;
                        });
                    }
                });
            });
        }

        private String getErrorSummary(String issue) {
            switch (issue) {
                case "no_api_key": return getString(R.string.error_recovery_no_api_key);
                case "no_im": return getString(R.string.error_recovery_no_im);
                case "not_installed": return getString(R.string.error_recovery_not_installed);
                case "gateway_down": return getString(R.string.error_recovery_gateway_down);
                case "no_prompt": return getString(R.string.error_recovery_no_prompt);
                default: return getString(R.string.error_recovery_all_ok);
            }
        }

        private void fixError(String issue) {
            switch (issue) {
                case "no_api_key":
                    showFragment(new LlmConfigFragment());
                    break;
                case "no_im":
                    startActivity(new Intent(requireContext(), FeishuSetupActivity.class));
                    break;
                case "not_installed":
                    startActivity(new Intent(requireContext(), HermesInstallActivity.class));
                    break;
                case "gateway_down":
                    requireContext().startService(new Intent(requireContext(), HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_START));
                    Toast.makeText(requireContext(), R.string.gateway_started, Toast.LENGTH_SHORT).show();
                    break;
                case "no_prompt":
                    showFragment(new LlmConfigFragment());
                    break;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateProfileDisplay();
            updateValidationDisplay();
            updateUsageStats();
        }

        private void updateUsageStats() {
            Preference uptimePref = findPreference("usage_total_uptime");
            Preference sessionsPref = findPreference("usage_sessions");
            Preference costPref = findPreference("usage_estimated_cost");

            int sessionCount = mConfigManager.getSessionCount(requireContext());
            long totalMs = mConfigManager.getTotalUptimeMs(requireContext());

            if (uptimePref != null) {
                String uptime = HermesGatewayService.formatDuration(totalMs);
                if (sessionCount > 0) {
                    uptimePref.setSummary(getString(R.string.usage_uptime_format, sessionCount, uptime));
                } else {
                    uptimePref.setSummary(getString(R.string.usage_no_data));
                }
            }

            if (sessionsPref != null) {
                sessionsPref.setSummary(sessionCount > 0
                        ? getString(R.string.usage_uptime_format, sessionCount, HermesGatewayService.formatDuration(totalMs))
                        : getString(R.string.usage_no_data));
            }

            if (costPref != null) {
                String provider = mConfigManager.getModelProvider();
                String costEst = getEstimatedCost(provider, totalMs);
                costPref.setSummary(getString(R.string.usage_cost_estimate, costEst, provider));
            }
        }

        private String getEstimatedCost(String provider, long uptimeMs) {
            // Rough estimate: ~100 messages/hour at ~500 tokens each
            long hours = uptimeMs / 3_600_000;
            long messages = hours * 100;
            switch (provider) {
                case "openai": return "$" + String.format("%.2f", messages * 0.001);
                case "anthropic": return "$" + String.format("%.2f", messages * 0.0015);
                case "google": return "$" + String.format("%.2f", messages * 0.0005);
                case "deepseek": return "$" + String.format("%.2f", messages * 0.0001);
                case "ollama": return "$0.00 (local)";
                default: return "$" + String.format("%.2f", messages * 0.001);
            }
        }

        private void showSaveProfileDialog() {
            android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setHint(R.string.profile_name_hint);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.profile_save_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            mConfigManager.saveProfile(requireContext(), name);
                            updateProfileDisplay();
                            Toast.makeText(requireContext(),
                                    getString(R.string.profile_saved, name), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void showSwitchProfileDialog() {
            java.util.List<String> profiles = mConfigManager.getProfileNames(requireContext());
            if (profiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.profile_no_profiles, Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = profiles.toArray(new String[0]);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.profile_switch_title)
                    .setItems(items, (d, which) -> {
                        String name = items[which];
                        if (mConfigManager.loadProfile(requireContext(), name)) {
                            HermesConfigManager.restartGatewayIfRunning(requireContext());
                            updateProfileDisplay();
                            Toast.makeText(requireContext(),
                                    getString(R.string.profile_loaded, name), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(requireContext(), R.string.profile_not_found, Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        }

        private void showDeleteProfileDialog() {
            java.util.List<String> profiles = mConfigManager.getProfileNames(requireContext());
            if (profiles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.profile_no_profiles, Toast.LENGTH_SHORT).show();
                return;
            }
            String[] items = profiles.toArray(new String[0]);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.profile_delete_title)
                    .setItems(items, (d, which) -> {
                        String name = items[which];
                        mConfigManager.deleteProfile(requireContext(), name);
                        updateProfileDisplay();
                        Toast.makeText(requireContext(),
                                getString(R.string.profile_deleted, name), Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }
    }

    public static class LlmConfigFragment extends PreferenceFragmentCompat {

        private HermesConfigManager mConfigManager;
        private boolean mHasUnsavedChanges = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_llm_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            String currentProvider = mConfigManager.getModelProvider();
            updateModelList(currentProvider);
            updateProviderHints(currentProvider);

            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref != null) {
                String currentKey = mConfigManager.getApiKey(currentProvider);
                if (currentKey != null && !currentKey.isEmpty()) {
                    apiKeyPref.setSummary(maskApiKey(currentKey));
                }
                apiKeyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setApiKey(mConfigManager.getModelProvider(), (String) newVal);
                    p.setSummary(maskApiKey((String) newVal));
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            Preference providerPref = findPreference("llm_provider");
            if (providerPref != null) {
                providerPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String provider = (String) newVal;
                    mConfigManager.setModelProvider(provider);
                    updateModelList(provider);
                    updateProviderHints(provider);

                    String key = mConfigManager.getApiKey(provider);
                    Preference akp = findPreference("llm_api_key");
                    if (akp != null) {
                        akp.setSummary(key != null ? maskApiKey(key) : "");
                    }

                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            Preference modelPref = findPreference("llm_model");
            if (modelPref != null) {
                modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelName((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                baseUrlPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("OPENAI_BASE_URL", (String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Preset selection
            ListPreference presetPref = findPreference("llm_preset");
            if (presetPref != null) {
                presetPref.setOnPreferenceChangeListener((p, newVal) -> {
                    applyPreset((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Temperature preference
            Preference tempPref = findPreference("llm_temperature");
            if (tempPref != null) {
                tempPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelTemperature(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Max tokens preference
            Preference maxTokensPref = findPreference("llm_max_tokens");
            if (maxTokensPref != null) {
                maxTokensPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelMaxTokens(Integer.parseInt((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Top P preference
            Preference topPPref = findPreference("llm_top_p");
            if (topPPref != null) {
                topPPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelTopP(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Frequency penalty preference
            Preference freqPenaltyPref = findPreference("llm_frequency_penalty");
            if (freqPenaltyPref != null) {
                freqPenaltyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelFrequencyPenalty(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // Presence penalty preference
            Preference presPenaltyPref = findPreference("llm_presence_penalty");
            if (presPenaltyPref != null) {
                presPenaltyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        mConfigManager.setModelPresencePenalty(Float.parseFloat((String) newVal));
                    } catch (NumberFormatException ignored) {}
                    mHasUnsavedChanges = true;
                    return true;
                });
            }

            // System prompt preference
            Preference sysPromptPref = findPreference("llm_system_prompt");
            if (sysPromptPref != null) {
                sysPromptPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setSystemPrompt((String) newVal);
                    mHasUnsavedChanges = true;
                    return true;
                });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            requireActivity().getOnBackPressedDispatcher().addCallback(
                    getViewLifecycleOwner(),
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            if (mHasUnsavedChanges) {
                                showUnsavedChangesDialog();
                            } else {
                                setEnabled(false);
                                requireActivity().onBackPressed();
                            }
                        }
                    });
        }

        private void showUnsavedChangesDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.config_unsaved_title)
                    .setMessage(R.string.config_unsaved_message)
                    .setPositiveButton(R.string.config_unsaved_save, (d, w) -> {
                        mHasUnsavedChanges = false;
                        Toast.makeText(requireContext(), R.string.config_changes_saved,
                                Toast.LENGTH_SHORT).show();
                        requireActivity().getSupportFragmentManager().popBackStack();
                    })
                    .setNegativeButton(R.string.config_unsaved_discard, (d, w) -> {
                        mHasUnsavedChanges = false;
                        reloadConfig();
                        requireActivity().getSupportFragmentManager().popBackStack();
                    })
                    .setNeutralButton(R.string.config_unsaved_cancel, null)
                    .show();
        }

        private void reloadConfig() {
            HermesConfigManager.reinitialize();
        }

        private void updateProviderHints(String provider) {
            // Update API key hint
            Preference apiKeyHint = findPreference("llm_api_key_hint");
            if (apiKeyHint != null) {
                apiKeyHint.setSummary(getApiKeyHint(provider));
            }

            // Update base URL hint
            Preference baseUrlHint = findPreference("llm_base_url_hint");
            if (baseUrlHint != null) {
                baseUrlHint.setSummary(getBaseUrlHint(provider));
            }

            // Update provider info
            Preference providerInfo = findPreference("llm_provider_info");
            if (providerInfo != null) {
                providerInfo.setSummary(getProviderInfo(provider));
            }

            // Update cost estimate
            Preference costPref = findPreference("llm_cost_estimate");
            if (costPref != null) {
                costPref.setSummary(getCostEstimate(provider));
            }

            // Show/hide base URL based on provider
            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                boolean needsUrl = "ollama".equals(provider) || "custom".equals(provider);
                baseUrlPref.setVisible(needsUrl);
            }

            // Show/hide custom model input
            Preference customModel = findPreference("llm_custom_model");
            if (customModel != null) {
                customModel.setVisible("custom".equals(provider));
            }
        }

        private String getApiKeyHint(String provider) {
            switch (provider) {
                case "openai":     return "OpenAI keys start with sk-";
                case "anthropic":  return "Anthropic keys start with sk-ant-";
                case "google":     return "Get your key from Google AI Studio";
                case "deepseek":   return "DeepSeek keys from platform.deepseek.com";
                case "openrouter": return "OpenRouter keys from openrouter.ai/keys";
                case "xai":        return "xAI keys from console.x.ai";
                case "alibaba":    return "DashScope keys from Aliyun console";
                case "mistral":    return "Mistral keys from console.mistral.ai";
                case "nvidia":     return "NVIDIA API key from build.nvidia.com";
                case "ollama":     return "No API key needed for local Ollama";
                default:           return "Enter your API key";
            }
        }

        private String getBaseUrlHint(String provider) {
            switch (provider) {
                case "openai":     return "https://api.openai.com/v1";
                case "anthropic":  return "https://api.anthropic.com/v1";
                case "google":     return "https://generativelanguage.googleapis.com/v1beta";
                case "deepseek":   return "https://api.deepseek.com/v1";
                case "openrouter": return "https://openrouter.ai/api/v1";
                case "xai":        return "https://api.x.ai/v1";
                case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1";
                case "mistral":    return "https://api.mistral.ai/v1";
                case "nvidia":     return "https://integrate.api.nvidia.com/v1";
                case "ollama":     return "http://localhost:11434/v1";
                default:           return "Leave empty for default provider URL";
            }
        }

        private String getProviderInfo(String provider) {
            switch (provider) {
                case "openai":     return "GPT-4o, o1, o3 models. Best general-purpose.";
                case "anthropic":  return "Claude models. Excellent for analysis and coding.";
                case "google":     return "Gemini models. Good multimodal capabilities.";
                case "deepseek":   return "Cost-effective. Strong reasoning models.";
                case "openrouter": return "Access multiple providers through one API.";
                case "xai":        return "Grok models from xAI.";
                case "alibaba":    return "Qwen models. Strong multilingual support.";
                case "mistral":    return "Mistral and Codestral models. EU-based.";
                case "nvidia":     return "NVIDIA-hosted open models via NIM.";
                case "ollama":     return "Run models locally. No API costs.";
                default:           return "Any OpenAI-compatible API endpoint.";
            }
        }

        private String getCostEstimate(String provider) {
            switch (provider) {
                case "openai":     return "~$2.50/1M input tokens (gpt-4o)";
                case "anthropic":  return "~$3.00/1M input tokens (claude-sonnet-4-6)";
                case "google":     return "~$1.25/1M input tokens (gemini-2.5-flash)";
                case "deepseek":   return "~$0.27/1M input tokens (deepseek-chat)";
                case "openrouter": return "Varies by model. Check openrouter.ai";
                case "xai":        return "~$3.00/1M input tokens (grok-3)";
                case "alibaba":    return "~$0.40/1M input tokens (qwen-max)";
                case "mistral":    return "~$2.00/1M input tokens (mistral-large)";
                case "nvidia":     return "Free tier available. Pay-per-use.";
                case "ollama":     return "Free (runs on your device)";
                default:           return "Depends on your provider pricing.";
            }
        }

        private void updateModelList(String provider) {
            ListPreference modelPref = findPreference("llm_model");
            if (modelPref == null) return;

            int arrayResId = getModelArrayResId(provider);
            if (arrayResId != 0) {
                CharSequence[] models = getResources().getTextArray(arrayResId);
                modelPref.setEntries(models);
                modelPref.setEntryValues(models);
                if (models.length > 0) {
                    modelPref.setValue(models[0].toString());
                    mConfigManager.setModelName(models[0].toString());
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

        private void applyPreset(String preset) {
            float temperature;
            int maxTokens;
            float topP;
            String systemPrompt;

            switch (preset) {
                case "creative":
                    temperature = 0.9f;
                    maxTokens = 2048;
                    topP = 0.95f;
                    systemPrompt = "You are a creative and imaginative AI assistant. Think outside the box, offer unique perspectives, and express ideas vividly.";
                    break;
                case "precise":
                    temperature = 0.2f;
                    maxTokens = 4096;
                    topP = 0.8f;
                    systemPrompt = "You are a precise and analytical AI assistant. Provide accurate, well-structured answers. Be concise and factual.";
                    break;
                case "code":
                    temperature = 0.2f;
                    maxTokens = 4096;
                    topP = 0.9f;
                    systemPrompt = "You are an expert software engineer. Write clean, efficient code. Explain your reasoning. Follow best practices and design patterns.";
                    break;
                case "balanced":
                default:
                    temperature = 0.7f;
                    maxTokens = 2048;
                    topP = 1.0f;
                    systemPrompt = "You are a helpful AI assistant. Be friendly, clear, and thorough in your responses.";
                    break;
            }

            mConfigManager.setModelTemperature(temperature);
            mConfigManager.setModelMaxTokens(maxTokens);
            mConfigManager.setModelTopP(topP);
            mConfigManager.setSystemPrompt(systemPrompt);

            // Update UI fields
            androidx.preference.EditTextPreference tempPref = findPreference("llm_temperature");
            if (tempPref != null) {
                tempPref.setText(String.valueOf(temperature));
            }
            androidx.preference.EditTextPreference maxTokPref = findPreference("llm_max_tokens");
            if (maxTokPref != null) {
                maxTokPref.setText(String.valueOf(maxTokens));
            }
            androidx.preference.EditTextPreference topPPref = findPreference("llm_top_p");
            if (topPPref != null) {
                topPPref.setText(String.valueOf(topP));
            }
            androidx.preference.EditTextPreference sysPref = findPreference("llm_system_prompt");
            if (sysPref != null) {
                sysPref.setText(systemPrompt);
            }
        }

        private String maskApiKey(String key) {
            if (key == null || key.length() < 8) return "****";
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if ("llm_test_connection".equals(key)) {
                testConnection(preference);
                return true;
            }
            if ("llm_get_api_key".equals(key)) {
                openProviderUrl(getApiKeyUrl());
                return true;
            }
            if ("llm_docs".equals(key)) {
                openProviderUrl(getDocsUrl());
                return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void openProviderUrl(String url) {
            if (url == null) return;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                startActivity(intent);
            } catch (Exception ignored) {}
        }

        private String getApiKeyUrl() {
            String provider = mConfigManager.getModelProvider();
            switch (provider) {
                case "openai":     return "https://platform.openai.com/api-keys";
                case "anthropic":  return "https://console.anthropic.com/settings/keys";
                case "google":     return "https://aistudio.google.com/apikey";
                case "deepseek":   return "https://platform.deepseek.com/api_keys";
                case "openrouter": return "https://openrouter.ai/keys";
                case "xai":        return "https://console.x.ai/";
                case "alibaba":    return "https://dashscope.console.aliyun.com/apiKey";
                case "mistral":    return "https://console.mistral.ai/api-keys/";
                case "nvidia":     return "https://build.nvidia.com/";
                case "ollama":     return "http://localhost:11434";
                default:           return null;
            }
        }

        private String getDocsUrl() {
            String provider = mConfigManager.getModelProvider();
            switch (provider) {
                case "openai":     return "https://platform.openai.com/docs";
                case "anthropic":  return "https://docs.anthropic.com/en/docs";
                case "google":     return "https://ai.google.dev/gemini-api/docs";
                case "deepseek":   return "https://api-docs.deepseek.com/";
                case "openrouter": return "https://openrouter.ai/docs";
                case "xai":        return "https://docs.x.ai/";
                case "alibaba":    return "https://help.aliyun.com/document_detail/2712195.html";
                case "mistral":    return "https://docs.mistral.ai/";
                case "nvidia":     return "https://build.nvidia.com/explore/discover";
                case "ollama":     return "https://github.com/ollama/ollama";
                default:           return null;
            }
        }

        private void testConnection(Preference testPref) {
            String provider = mConfigManager.getModelProvider();
            String apiKey = mConfigManager.getApiKey(provider);
            String model = mConfigManager.getModelName();

            if ("ollama".equals(provider)) {
                apiKey = "ollama";
            } else if (apiKey.isEmpty()) {
                testPref.setSummary(getString(R.string.llm_test_no_key));
                return;
            }

            testPref.setSummary(getString(R.string.llm_test_running));
            String finalApiKey = apiKey;

            new Thread(() -> {
                // Phase 1: Basic connectivity check (existing validation)
                String[] result = performConnectionTest(provider, finalApiKey, model);
                if (!result[0].equals("success")) {
                    requireActivity().runOnUiThread(() -> {
                        if (result[0].equals("auth")) {
                            testPref.setSummary(getString(R.string.llm_test_fail_auth));
                        } else if (result[0].equals("network")) {
                            testPref.setSummary(getString(R.string.llm_test_fail_network));
                        } else {
                            testPref.setSummary(getString(R.string.llm_test_fail_generic, result[1]));
                        }
                    });
                    return;
                }

                // Phase 2: Model response test (streaming test)
                requireActivity().runOnUiThread(() ->
                        testPref.setSummary(getString(R.string.llm_test_streaming)));
                String[] streamResult = performModelResponseTest(provider, finalApiKey, model);
                requireActivity().runOnUiThread(() -> {
                    if (streamResult[0].equals("success_fast")) {
                        testPref.setSummary(getString(R.string.llm_test_success_fast,
                                streamResult[1], Integer.parseInt(streamResult[2])));
                    } else if (streamResult[0].equals("success_slow")) {
                        testPref.setSummary(getString(R.string.llm_test_success_slow,
                                Integer.parseInt(streamResult[2])));
                    } else if (streamResult[0].equals("no_response")) {
                        testPref.setSummary(getString(R.string.llm_test_fail_no_response));
                    } else {
                        // Fallback: basic validation succeeded, show basic success
                        if ("ollama".equals(provider)) {
                            testPref.setSummary(getString(R.string.llm_test_success_no_key, provider));
                        } else {
                            testPref.setSummary(getString(R.string.llm_test_success, model));
                        }
                    }
                });
            }).start();
        }

        private String[] performConnectionTest(String provider, String apiKey, String model) {
            try {
                String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String curlPath = binPath + "/curl";

                if (!new File(curlPath).exists()) {
                    return new String[]{"generic", "curl not available"};
                }

                String url = getProviderTestUrl(provider);
                if (url == null) {
                    return new String[]{"generic", "Unknown provider: " + provider};
                }

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
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String output = reader.readLine();
                p.waitFor();

                int httpCode = 0;
                try {
                    httpCode = Integer.parseInt(output != null ? output.trim() : "0");
                } catch (NumberFormatException ignored) {}

                if (httpCode == 200) {
                    return new String[]{"success", model};
                } else if (httpCode == 401 || httpCode == 403) {
                    return new String[]{"auth", "" + httpCode};
                } else if (httpCode == 0) {
                    return new String[]{"network", "no response"};
                } else {
                    return new String[]{"generic", "HTTP " + httpCode};
                }
            } catch (Exception e) {
                return new String[]{"network", e.getMessage()};
            }
        }

        private String getProviderTestUrl(String provider) {
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

        /**
         * Phase 2: Send a minimal chat completion request to verify the model can actually respond.
         * Returns String[] with:
         *   [0] = "success_fast" | "success_slow" | "no_response" | "error"
         *   [1] = model name (on success)
         *   [2] = response time in ms (on success)
         */
        private String[] performModelResponseTest(String provider, String apiKey, String model) {
            try {
                String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                String curlPath = binPath + "/curl";

                if (!new File(curlPath).exists()) {
                    return new String[]{"error", "curl not available", "0"};
                }

                String baseUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
                String url = getProviderChatUrl(provider, model, baseUrl);
                if (url == null) {
                    return new String[]{"error", "Unknown provider for chat test", "0"};
                }

                // Build the curl command based on provider
                ProcessBuilder pb;
                if ("ollama".equals(provider)) {
                    String jsonBody = "{\"model\":\"" + model + "\",\"prompt\":\"Say OK\",\"stream\":false}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-d", jsonBody);
                } else if ("google".equals(provider)) {
                    String jsonBody = "{\"contents\":[{\"parts\":[{\"text\":\"Say OK\"}]}],\"generationConfig\":{\"maxOutputTokens\":10}}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-d", jsonBody);
                } else {
                    // OpenAI-compatible providers
                    String jsonBody = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Say OK\"}],\"max_tokens\":10,\"stream\":false}";
                    pb = new ProcessBuilder(curlPath, "-s", "-w", "\n%{http_code}",
                            "--connect-timeout", "10", "--max-time", "30",
                            "-X", "POST", url,
                            "-H", "Content-Type: application/json",
                            "-H", "Authorization: Bearer " + apiKey,
                            "-d", jsonBody);
                }

                pb.environment().put("PATH", binPath + ":/system/bin");
                pb.redirectErrorStream(true);

                long startTime = System.currentTimeMillis();
                Process p = pb.start();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                p.waitFor();
                long elapsed = System.currentTimeMillis() - startTime;

                String fullOutput = output.toString().trim();
                // Last line is the HTTP code from -w flag
                String[] lines = fullOutput.split("\n");
                String lastLine = lines.length > 0 ? lines[lines.length - 1].trim() : "";
                int httpCode = 0;
                try {
                    httpCode = Integer.parseInt(lastLine);
                } catch (NumberFormatException ignored) {}

                // Reconstruct the response body (everything except the last line)
                String responseBody = "";
                if (lines.length > 1) {
                    StringBuilder bodyBuilder = new StringBuilder();
                    for (int i = 0; i < lines.length - 1; i++) {
                        bodyBuilder.append(lines[i]);
                    }
                    responseBody = bodyBuilder.toString();
                }

                if (httpCode == 200 && !responseBody.isEmpty()) {
                    // Check that the response contains actual content
                    boolean hasContent = responseBody.contains("choices")
                            || responseBody.contains("content")
                            || responseBody.contains("response")
                            || responseBody.contains("candidates")
                            || responseBody.contains("OK");
                    if (hasContent) {
                        if (elapsed < 10000) {
                            return new String[]{"success_fast", model, String.valueOf(elapsed)};
                        } else {
                            return new String[]{"success_slow", model, String.valueOf(elapsed)};
                        }
                    } else {
                        return new String[]{"no_response", "", "0"};
                    }
                } else if (httpCode == 401 || httpCode == 403) {
                    return new String[]{"error", "Auth failed in model test", "0"};
                } else if (httpCode == 404) {
                    return new String[]{"no_response", "Model not found", "0"};
                } else if (httpCode == 0) {
                    return new String[]{"error", "No response from server", "0"};
                } else {
                    return new String[]{"no_response", "HTTP " + httpCode, "0"};
                }
            } catch (Exception e) {
                return new String[]{"error", e.getMessage() != null ? e.getMessage() : "exception", "0"};
            }
        }

        /**
         * Get the chat completion endpoint URL for a given provider.
         */
        private String getProviderChatUrl(String provider, String model, String baseUrl) {
            switch (provider) {
                case "openai":     return "https://api.openai.com/v1/chat/completions";
                case "anthropic":  return "https://api.anthropic.com/v1/chat/completions";
                case "deepseek":   return "https://api.deepseek.com/v1/chat/completions";
                case "openrouter": return "https://openrouter.ai/api/v1/chat/completions";
                case "xai":        return "https://api.x.ai/v1/chat/completions";
                case "alibaba":    return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                case "mistral":    return "https://api.mistral.ai/v1/chat/completions";
                case "nvidia":     return "https://integrate.api.nvidia.com/v1/chat/completions";
                case "google":     return "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
                case "ollama":     return baseUrl.isEmpty() ? "http://localhost:11434/api/generate" : baseUrl + "/api/generate";
                case "custom":     return baseUrl.isEmpty() ? null : baseUrl + "/chat/completions";
                default:           return null;
            }
        }
    }

    public static class GatewayControlFragment extends PreferenceFragmentCompat {
        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_gateway_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // Stats display
            updateStatsDisplay();

            // Auto-restart toggle
            Preference autoRestartPref = findPreference("gateway_auto_restart");
            if (autoRestartPref != null) {
                autoRestartPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_AUTO_RESTART", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Max restarts
            Preference maxRestartsPref = findPreference("gateway_max_restarts");
            if (maxRestartsPref != null) {
                maxRestartsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_MAX_RESTARTS", (String) newVal);
                    return true;
                });
            }

            // Restart delay
            Preference restartDelayPref = findPreference("gateway_restart_delay");
            if (restartDelayPref != null) {
                restartDelayPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("GATEWAY_RESTART_DELAY", (String) newVal);
                    return true;
                });
            }

        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if (key == null) return super.onPreferenceTreeClick(preference);

            switch (key) {
                case "gateway_start":
                    runGatewayCommand("start");
                    return true;
                case "gateway_stop":
                    runGatewayCommand("stop");
                    return true;
                case "gateway_restart":
                    runGatewayCommand("restart");
                    return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void runGatewayCommand(String action) {
            Context ctx = requireContext();
            switch (action) {
                case "start":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_START));
                    Toast.makeText(ctx, R.string.gateway_started, Toast.LENGTH_SHORT).show();
                    break;
                case "stop":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_STOP));
                    Toast.makeText(ctx, R.string.gateway_stopped, Toast.LENGTH_SHORT).show();
                    break;
                case "restart":
                    ctx.startService(new Intent(ctx, HermesGatewayService.class)
                            .setAction(HermesGatewayService.ACTION_STOP));
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        ctx.startService(new Intent(ctx, HermesGatewayService.class)
                                .setAction(HermesGatewayService.ACTION_START));
                    }, 1500);
                    Toast.makeText(ctx, R.string.gateway_restarted, Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateStatsDisplay();
        }

        private void updateStatsDisplay() {
            boolean running = HermesGatewayService.isRunning();
            String uptime = HermesGatewayService.getFormattedUptime();

            Preference statsPref = findPreference("gateway_stats");
            if (statsPref != null) {
                if (running && !uptime.isEmpty()) {
                    statsPref.setSummary(getString(R.string.gateway_stats_running, uptime));
                } else {
                    statsPref.setSummary(getString(R.string.gateway_stats_stopped));
                }
            }
        }
    }

    public static class AgentSettingsFragment extends PreferenceFragmentCompat {
        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_agent_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // Context compression
            SwitchPreferenceCompat compressionPref = findPreference("agent_compression_enabled");
            if (compressionPref != null) {
                String val = mConfigManager.getYamlValue("compression.enabled", "true");
                compressionPref.setChecked(!"false".equals(val));
                compressionPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("compression.enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Compression threshold
            EditTextPreference thresholdPref = findPreference("agent_compression_threshold");
            if (thresholdPref != null) {
                String threshold = mConfigManager.getYamlValue("compression.threshold", "");
                if (!threshold.isEmpty()) thresholdPref.setText(threshold);
                thresholdPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("compression.threshold", (String) newVal);
                    return true;
                });
            }

            // Context length
            EditTextPreference contextLenPref = findPreference("agent_context_length");
            if (contextLenPref != null) {
                String ctxLen = mConfigManager.getYamlValue("context_length", "");
                if (!ctxLen.isEmpty()) contextLenPref.setText(ctxLen);
                contextLenPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("context_length", (String) newVal);
                    return true;
                });
            }

            // Max turns
            EditTextPreference maxTurnsPref = findPreference("agent_max_turns");
            if (maxTurnsPref != null) {
                String maxTurns = mConfigManager.getYamlValue("agent.max_turns", "");
                if (!maxTurns.isEmpty()) maxTurnsPref.setText(maxTurns);
                maxTurnsPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.max_turns", (String) newVal);
                    return true;
                });
            }

            // Gateway timeout
            EditTextPreference timeoutPref = findPreference("agent_gateway_timeout");
            if (timeoutPref != null) {
                String timeout = mConfigManager.getYamlValue("agent.gateway_timeout", "");
                if (!timeout.isEmpty()) timeoutPref.setText(timeout);
                timeoutPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.gateway_timeout", (String) newVal);
                    return true;
                });
            }

            // Verbose
            SwitchPreferenceCompat verbosePref = findPreference("agent_verbose");
            if (verbosePref != null) {
                String val = mConfigManager.getYamlValue("agent.verbose", "false");
                verbosePref.setChecked("true".equals(val));
                verbosePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("agent.verbose", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Memory enabled
            SwitchPreferenceCompat memoryPref = findPreference("agent_memory_enabled");
            if (memoryPref != null) {
                String val = mConfigManager.getYamlValue("memory.memory_enabled", "true");
                memoryPref.setChecked(!"false".equals(val));
                memoryPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.memory_enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // User profile
            SwitchPreferenceCompat profilePref = findPreference("agent_user_profile_enabled");
            if (profilePref != null) {
                String val = mConfigManager.getYamlValue("memory.user_profile_enabled", "true");
                profilePref.setChecked(!"false".equals(val));
                profilePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.user_profile_enabled", (Boolean) newVal ? "true" : "false");
                    return true;
                });
            }

            // Memory char limit
            EditTextPreference memLimitPref = findPreference("agent_memory_char_limit");
            if (memLimitPref != null) {
                String limit = mConfigManager.getYamlValue("memory.memory_char_limit", "");
                if (!limit.isEmpty()) memLimitPref.setText(limit);
                memLimitPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("memory.memory_char_limit", (String) newVal);
                    return true;
                });
            }

            // Session reset mode
            ListPreference resetModePref = findPreference("agent_session_reset_mode");
            if (resetModePref != null) {
                String mode = mConfigManager.getYamlValue("session_reset.mode", "none");
                resetModePref.setValue(mode);
                resetModePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.mode", (String) newVal);
                    return true;
                });
            }

            // Session idle minutes
            EditTextPreference idlePref = findPreference("agent_session_idle_minutes");
            if (idlePref != null) {
                String idle = mConfigManager.getYamlValue("session_reset.idle_minutes", "");
                if (!idle.isEmpty()) idlePref.setText(idle);
                idlePref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.idle_minutes", (String) newVal);
                    return true;
                });
            }

            // Session reset hour
            EditTextPreference hourPref = findPreference("agent_session_reset_hour");
            if (hourPref != null) {
                String hour = mConfigManager.getYamlValue("session_reset.at_hour", "");
                if (!hour.isEmpty()) hourPref.setText(hour);
                hourPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setYamlValue("session_reset.at_hour", (String) newVal);
                    return true;
                });
            }
        }
    }
}
