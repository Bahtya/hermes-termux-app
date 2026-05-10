package com.termux.app.hermes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

public class HermesConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_config);
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
        private final android.os.Handler mUptimeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        private boolean mGatewayRunning = false;

        private final Runnable mUptimeUpdater = new Runnable() {
            @Override
            public void run() {
                if (!mGatewayRunning || getActivity() == null) return;
                Preference dashGateway = findPreference("hermes_dashboard_gateway");
                if (dashGateway != null && mGatewayRunning) {
                    String uptime = HermesGatewayService.getFormattedUptime();
                    dashGateway.setSummary(getString(R.string.dashboard_gateway_running) + " — " + uptime);
                }
                mUptimeHandler.postDelayed(this, 10_000);
            }
        };

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            // --- Dashboard: Gateway status with uptime ---
            Preference dashGateway = findPreference("hermes_dashboard_gateway");
            if (dashGateway != null) {
                HermesGatewayStatus.checkAsync((status, detail) -> {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        switch (status) {
                            case RUNNING:
                                mGatewayRunning = true;
                                String uptime = HermesGatewayService.getFormattedUptime();
                                dashGateway.setSummary(getString(R.string.dashboard_gateway_running) + " — " + uptime);
                                mUptimeHandler.removeCallbacks(mUptimeUpdater);
                                mUptimeHandler.postDelayed(mUptimeUpdater, 10_000);
                                break;
                            case NOT_INSTALLED:
                                mGatewayRunning = false;
                                dashGateway.setSummary(getString(R.string.dashboard_gateway_not_installed));
                                break;
                            default:
                                mGatewayRunning = false;
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
                if (platforms.isEmpty()) {
                    dashIm.setSummary(getString(R.string.dashboard_im_none));
                } else {
                    dashIm.setSummary(getString(R.string.dashboard_im_list,
                            android.text.TextUtils.join(", ", platforms)));
                }
            }

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
                        ? getString(R.string.feishu_configured)
                        : getString(R.string.feishu_not_configured));
            }

            // Show Telegram status
            Preference telegramPref = findPreference("hermes_telegram_setup");
            if (telegramPref != null) {
                boolean configured = !mConfigManager.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty();
                telegramPref.setSummary(configured
                        ? getString(R.string.telegram_configured)
                        : getString(R.string.telegram_not_configured));
            }

            // Show Discord status
            Preference discordPref = findPreference("hermes_discord_setup");
            if (discordPref != null) {
                boolean configured = !mConfigManager.getEnvVar("DISCORD_BOT_TOKEN").isEmpty();
                discordPref.setSummary(configured
                        ? getString(R.string.discord_configured)
                        : getString(R.string.discord_not_configured));
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
                case "hermes_gateway_control":
                    showFragment(new GatewayControlFragment());
                    return true;
                case "hermes_check_update":
                    checkForUpdate(preference);
                    return true;
                case "hermes_gateway_log":
                    startActivity(new Intent(requireContext(), GatewayLogActivity.class));
                    return true;
                case "hermes_share_diagnostics":
                    shareDiagnostics();
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
                case "hermes_battery_opt":
                    requestBatteryOptimization(preference);
                    return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void showFragment(Fragment fragment) {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.hermes_config_content, fragment)
                    .addToBackStack(null)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .commit();
        }

        private void requestBatteryOptimization(Preference pref) {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(Context.POWER_SERVICE);
            String packageName = requireContext().getPackageName();
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                if (pref != null) pref.setSummary(getString(R.string.battery_opt_already_whitelisted));
                return;
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.battery_opt_prompt_title)
                    .setMessage(R.string.battery_opt_prompt_message)
                    .setPositiveButton(R.string.battery_opt_prompt_positive, (d, w) -> {
                        try {
                            Intent intent = new Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(android.net.Uri.parse("package:" + packageName));
                            startActivity(intent);
                        } catch (Exception e) {
                            // Fallback: open battery optimization settings
                            Intent fallback = new Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(fallback);
                        }
                    })
                    .setNegativeButton(R.string.battery_opt_prompt_negative, null)
                    .show();
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
                                updatePref.setSummary(getString(R.string.hermes_update_available, latest, current));
                            } else {
                                updatePref.setSummary(getString(R.string.hermes_up_to_date, current));
                            }
                        }
                    }
                });
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
                return new String[]{pipVersion != null ? pipVersion : current, null};
            } catch (Exception e) {
                return null;
            }
        }

        private void shareDiagnostics() {
            HermesConfigManager config = HermesConfigManager.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("=== Hermes Termux Diagnostic Info ===\n\n");

            // App version
            try {
                String versionName = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                sb.append("App Version: ").append(versionName).append("\n");
            } catch (Exception e) {
                sb.append("App Version: unknown\n");
            }

            // Gateway status
            sb.append("Gateway: ");
            if (HermesGatewayService.getUptime() > 0) {
                sb.append("Running (uptime: ").append(HermesGatewayService.getFormattedUptime()).append(")\n");
            } else {
                sb.append("Stopped\n");
            }

            // LLM config
            String provider = config.getModelProvider();
            String model = config.getModelName();
            String apiKey = config.getApiKey(provider);
            String maskedKey = (apiKey != null && apiKey.length() >= 8)
                    ? apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4)
                    : (apiKey != null && !apiKey.isEmpty() ? "****" : "(not set)");
            sb.append("LLM Provider: ").append(provider).append("\n");
            sb.append("Model: ").append(model).append("\n");
            sb.append("API Key: ").append(maskedKey).append("\n");
            sb.append("Base URL: ").append(config.getEnvVar("OPENAI_BASE_URL")).append("\n\n");

            // IM platforms
            sb.append("Feishu: ").append(config.isFeishuConfigured() ? "Configured" : "Not configured").append("\n");
            String tgToken = config.getEnvVar("TELEGRAM_BOT_TOKEN");
            sb.append("Telegram: ").append(tgToken.isEmpty() ? "Not configured" : "Configured (token: "
                    + tgToken.substring(0, Math.min(4, tgToken.length())) + "...****)").append("\n");
            String dcToken = config.getEnvVar("DISCORD_BOT_TOKEN");
            sb.append("Discord: ").append(dcToken.isEmpty() ? "Not configured" : "Configured (token: "
                    + dcToken.substring(0, Math.min(4, dcToken.length())) + "...****)").append("\n");

            String content = sb.toString();

            // Show dialog with share and copy options
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.hermes_share_diag_title)
                    .setMessage(content)
                    .setPositiveButton("Share", (d, w) -> {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, content);
                        startActivity(Intent.createChooser(shareIntent, "Share Diagnostics"));
                    })
                    .setNeutralButton("Copy", (d, w) -> {
                        ClipboardManager clipboard = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics", content));
                        Toast.makeText(requireContext(), R.string.hermes_diag_copied, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
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

        @Override
        public void onDestroy() {
            super.onDestroy();
            mUptimeHandler.removeCallbacks(mUptimeUpdater);
        }
    }

    public static class LlmConfigFragment extends PreferenceFragmentCompat {

        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_llm_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            String currentProvider = mConfigManager.getModelProvider();
            updateModelList(currentProvider);

            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref != null) {
                String currentKey = mConfigManager.getApiKey(currentProvider);
                if (currentKey != null && !currentKey.isEmpty()) {
                    apiKeyPref.setSummary(maskApiKey(currentKey));
                }
                apiKeyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setApiKey(mConfigManager.getModelProvider(), (String) newVal);
                    p.setSummary(maskApiKey((String) newVal));
                    restartGatewayIfRunning();
                    return true;
                });
            }

            Preference providerPref = findPreference("llm_provider");
            if (providerPref != null) {
                providerPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String provider = (String) newVal;
                    mConfigManager.setModelProvider(provider);
                    updateModelList(provider);

                    String key = mConfigManager.getApiKey(provider);
                    Preference akp = findPreference("llm_api_key");
                    if (akp != null) {
                        akp.setSummary(key != null ? maskApiKey(key) : "");
                    }

                    Preference baseUrlPref = findPreference("llm_base_url");
                    if (baseUrlPref != null) {
                        boolean needsUrl = "ollama".equals(provider) || "custom".equals(provider);
                        baseUrlPref.setVisible(needsUrl);
                    }
                    restartGatewayIfRunning();
                    return true;
                });
            }

            Preference modelPref = findPreference("llm_model");
            if (modelPref != null) {
                modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelName((String) newVal);
                    restartGatewayIfRunning();
                    return true;
                });
            }

            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                baseUrlPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("OPENAI_BASE_URL", (String) newVal);
                    return true;
                });
                boolean needsUrl = "ollama".equals(currentProvider) || "custom".equals(currentProvider);
                baseUrlPref.setVisible(needsUrl);
            }

            // System prompt
            Preference systemPromptPref = findPreference("llm_system_prompt");
            if (systemPromptPref != null) {
                String currentPrompt = mConfigManager.getModelSystemPrompt();
                if (!currentPrompt.isEmpty() && systemPromptPref instanceof androidx.preference.EditTextPreference) {
                    ((androidx.preference.EditTextPreference) systemPromptPref).setText(currentPrompt);
                    systemPromptPref.setSummary(currentPrompt.length() > 60
                            ? currentPrompt.substring(0, 60) + "…"
                            : currentPrompt);
                }
                systemPromptPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String prompt = (String) newVal;
                    mConfigManager.setModelSystemPrompt(prompt);
                    p.setSummary(prompt.isEmpty() ? getString(R.string.llm_system_prompt_summary)
                            : (prompt.length() > 60 ? prompt.substring(0, 60) + "…" : prompt));
                    return true;
                });
            }
        }

        private void showPersonaTemplateDialog() {
            String[] names = getResources().getStringArray(R.array.llm_persona_names);
            String[] prompts = getResources().getStringArray(R.array.llm_persona_prompts);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.llm_persona_template_title)
                    .setItems(names, (dialog, which) -> {
                        if (which < prompts.length) {
                            String selectedPrompt = prompts[which];
                            if (!selectedPrompt.isEmpty()) {
                                mConfigManager.setModelSystemPrompt(selectedPrompt);
                                Preference sp = findPreference("llm_system_prompt");
                                if (sp instanceof androidx.preference.EditTextPreference) {
                                    ((androidx.preference.EditTextPreference) sp).setText(selectedPrompt);
                                }
                                if (sp != null) {
                                    sp.setSummary(selectedPrompt.length() > 60
                                            ? selectedPrompt.substring(0, 60) + "…"
                                            : selectedPrompt);
                                }
                            }
                        }
                    })
                    .show();
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

        private String maskApiKey(String key) {
            if (key == null || key.length() < 8) return "****";
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }

        private void restartGatewayIfRunning() {
            HermesGatewayStatus.checkAsync((status, detail) -> {
                if (status == HermesGatewayStatus.Status.RUNNING && getActivity() != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (getActivity() == null) return;
                        Context ctx = getActivity();
                        ctx.startService(new Intent(ctx, HermesGatewayService.class)
                                .setAction(HermesGatewayService.ACTION_STOP));
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            ctx.startService(new Intent(ctx, HermesGatewayService.class)
                                    .setAction(HermesGatewayService.ACTION_START));
                        }, 1500);
                        Toast.makeText(ctx, R.string.gateway_restart_for_config, Toast.LENGTH_SHORT).show();
                    }, 500);
                }
            });
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if ("llm_test_connection".equals(key)) {
                testConnection(preference);
                return true;
            }
            if ("llm_persona_template".equals(key)) {
                showPersonaTemplateDialog();
                return true;
            }
            return super.onPreferenceTreeClick(preference);
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
                String[] result = performConnectionTest(provider, finalApiKey, model);
                requireActivity().runOnUiThread(() -> {
                    if (result[0].equals("success")) {
                        if ("ollama".equals(provider)) {
                            testPref.setSummary(getString(R.string.llm_test_success_no_key, provider));
                        } else {
                            testPref.setSummary(getString(R.string.llm_test_success, model));
                        }
                    } else if (result[0].equals("auth")) {
                        testPref.setSummary(getString(R.string.llm_test_fail_auth));
                    } else if (result[0].equals("network")) {
                        testPref.setSummary(getString(R.string.llm_test_fail_network));
                    } else {
                        testPref.setSummary(getString(R.string.llm_test_fail_generic, result[1]));
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
    }

    public static class GatewayControlFragment extends PreferenceFragmentCompat {

        private androidx.activity.result.ActivityResultLauncher<String> mNotifPermLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mNotifPermLauncher = registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        Preference permPref = findPreference("gateway_notif_permission");
                        if (permPref != null) {
                            permPref.setSummary(isGranted
                                    ? getString(R.string.gateway_notif_perm_granted)
                                    : getString(R.string.gateway_notif_perm_denied));
                        }
                    });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_gateway_preferences, rootKey);
            updatePermSummary();
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            String key = preference.getKey();
            if (key == null) return super.onPreferenceTreeClick(preference);

            switch (key) {
                case "gateway_start":
                    ensureNotificationPermissionThen(() -> runGatewayCommand("start"));
                    return true;
                case "gateway_stop":
                    runGatewayCommand("stop");
                    return true;
                case "gateway_restart":
                    ensureNotificationPermissionThen(() -> runGatewayCommand("restart"));
                    return true;
                case "gateway_notif_permission":
                    requestNotificationPermission();
                    return true;
            }
            return super.onPreferenceTreeClick(preference);
        }

        private void ensureNotificationPermissionThen(Runnable action) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    action.run();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.gateway_notif_perm_rationale_title)
                            .setMessage(R.string.gateway_notif_perm_rationale_message)
                            .setPositiveButton(R.string.gateway_notif_perm_enable, (d, w) -> {
                                mNotifPermLauncher.launch("android.permission.POST_NOTIFICATIONS");
                                action.run();
                            })
                            .setNegativeButton(R.string.gateway_notif_perm_skip, (d, w) -> action.run())
                            .show();
                }
            } else {
                action.run();
            }
        }

        private void requestNotificationPermission() {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Preference permPref = findPreference("gateway_notif_permission");
                    if (permPref != null) permPref.setSummary(getString(R.string.gateway_notif_perm_granted));
                } else {
                    mNotifPermLauncher.launch("android.permission.POST_NOTIFICATIONS");
                }
            } else {
                Preference permPref = findPreference("gateway_notif_permission");
                if (permPref != null) permPref.setSummary(getString(R.string.gateway_notif_perm_granted));
            }
        }

        private void updatePermSummary() {
            Preference permPref = findPreference("gateway_notif_permission");
            if (permPref == null) return;
            if (android.os.Build.VERSION.SDK_INT < 33) {
                permPref.setSummary(getString(R.string.gateway_notif_perm_granted));
            } else {
                boolean granted = requireContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                permPref.setSummary(granted
                        ? getString(R.string.gateway_notif_perm_granted)
                        : getString(R.string.gateway_notif_perm_summary));
            }
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
    }
}
