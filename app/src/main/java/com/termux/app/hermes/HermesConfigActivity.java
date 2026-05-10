package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
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

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();
            refreshDashboard();
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshDashboard();
        }

        private void refreshDashboard() {
            if (mConfigManager == null) return;
            mConfigManager.loadConfig();

            // --- Dashboard: Version ---
            Preference versionPref = findPreference("hermes_dashboard_version");
            if (versionPref != null) {
                fetchAndDisplayVersion(versionPref);
            }

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

            // Battery optimization status
            Preference batteryPref = findPreference("hermes_battery_optimization");
            if (batteryPref != null) {
                PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
                boolean isWhitelisted = pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
                batteryPref.setSummary(isWhitelisted
                        ? getString(R.string.battery_optimization_summary_on)
                        : getString(R.string.battery_optimization_summary_off));
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
                case "hermes_battery_optimization":
                    showBatteryOptimizationDialog();
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
                case "hermes_config_summary":
                    showConfigSummary();
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
            }
            return super.onPreferenceTreeClick(preference);
        }

        @Override
        public void onResume() {
            super.onResume();
            // Refresh battery optimization status when returning from settings
            Preference batteryPref = findPreference("hermes_battery_optimization");
            if (batteryPref != null) {
                PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
                boolean isWhitelisted = pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
                batteryPref.setSummary(isWhitelisted
                        ? getString(R.string.battery_optimization_summary_on)
                        : getString(R.string.battery_optimization_summary_off));
            }
        }

        private void showBatteryOptimizationDialog() {
            PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName())) {
                Toast.makeText(requireContext(), R.string.battery_optimization_summary_on, Toast.LENGTH_SHORT).show();
                return;
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.battery_optimization_prompt_title)
                    .setMessage(R.string.battery_optimization_prompt_message)
                    .setPositiveButton(R.string.battery_optimization_open_settings, (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(fallback);
                        }
                    })
                    .setNegativeButton(R.string.battery_optimization_skip, null)
                    .show();
        }

        private void showFragment(Fragment fragment) {
            requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.hermes_config_content, fragment)
                    .addToBackStack(null)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .commit();
        }

        private void fetchAndDisplayVersion(Preference versionPref) {
            new Thread(() -> {
                String version = null;
                try {
                    String binPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
                    String hermesPath = binPath + "/hermes";
                    if (new java.io.File(hermesPath).exists()) {
                        ProcessBuilder pb = new ProcessBuilder(binPath + "/bash", "-c",
                                hermesPath + " --version 2>/dev/null | head -1");
                        pb.environment().put("PATH", binPath + ":/system/bin");
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()));
                        version = reader.readLine();
                        if (version != null) version = version.trim();
                        p.waitFor();
                    }
                } catch (Exception ignored) {}

                if (getActivity() == null) return;
                String finalVersion = version;
                getActivity().runOnUiThread(() -> {
                    if (finalVersion != null && !finalVersion.isEmpty()) {
                        versionPref.setSummary(getString(R.string.dashboard_version_format, finalVersion));
                    } else {
                        versionPref.setSummary(getString(R.string.dashboard_version_not_installed));
                    }
                });
            }).start();
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

        private void showConfigSummary() {
            HermesConfigManager cfg = mConfigManager;
            String provider = cfg.getModelProvider();
            String apiKey = cfg.getApiKey(provider);
            String model = cfg.getModelName();
            String persona = cfg.getSelectedPersona();

            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.config_summary_section_llm)).append("\n");
            sb.append("  Provider: ").append(provider).append("\n");
            sb.append("  Model: ").append(model).append("\n");
            sb.append("  API Key: ");
            sb.append(apiKey != null && !apiKey.isEmpty()
                    ? maskApiKey(apiKey) : getString(R.string.config_summary_key_not_set));
            sb.append("\n");

            String baseUrl = cfg.getEnvVar("OPENAI_BASE_URL");
            if (!baseUrl.isEmpty()) {
                sb.append("  Base URL: ").append(baseUrl).append("\n");
            }

            sb.append("\n").append(getString(R.string.config_summary_section_im)).append("\n");
            if (cfg.isFeishuConfigured()) {
                sb.append("  Feishu/Lark: ").append(getString(R.string.config_summary_key_set)).append("\n");
            }
            if (!cfg.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()) {
                sb.append("  Telegram: ").append(getString(R.string.config_summary_key_set)).append("\n");
            }
            if (!cfg.getEnvVar("DISCORD_BOT_TOKEN").isEmpty()) {
                sb.append("  Discord: ").append(getString(R.string.config_summary_key_set)).append("\n");
            }
            boolean anyIm = cfg.isFeishuConfigured()
                    || !cfg.getEnvVar("TELEGRAM_BOT_TOKEN").isEmpty()
                    || !cfg.getEnvVar("DISCORD_BOT_TOKEN").isEmpty();
            if (!anyIm) {
                sb.append("  ").append(getString(R.string.config_summary_key_not_set)).append("\n");
            }

            if (!persona.isEmpty()) {
                sb.append("\n").append(getString(R.string.config_summary_section_persona)).append("\n");
                sb.append("  ").append(persona.substring(0, 1).toUpperCase())
                        .append(persona.substring(1)).append("\n");
            }

            String summary = sb.toString();

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.config_summary_title)
                    .setMessage(summary)
                    .setPositiveButton(R.string.config_summary_share, (d, w) -> {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, summary);
                        startActivity(Intent.createChooser(shareIntent,
                                getString(R.string.config_summary_title)));
                    })
                    .setNegativeButton(android.R.string.ok, null)
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
                    HermesConfigManager.restartGatewayIfRunning(requireContext());
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
                    HermesConfigManager.restartGatewayIfRunning(requireContext());
                    return true;
                });
            }

            Preference modelPref = findPreference("llm_model");
            if (modelPref != null) {
                modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    String model = (String) newVal;
                    boolean isCustom = getString(R.string.llm_model_custom).equals(model);
                    Preference customModelPref = findPreference("llm_custom_model");
                    if (isCustom) {
                        if (customModelPref != null) customModelPref.setVisible(true);
                    } else {
                        mConfigManager.setModelName(model);
                        if (customModelPref != null) customModelPref.setVisible(false);
                    }
                    return true;
                });
            }

            Preference customModelPref = findPreference("llm_custom_model");
            if (customModelPref != null) {
                String currentModel = mConfigManager.getModelName();
                boolean modelInList = isModelInPresetList(currentProvider, currentModel);
                if (!modelInList && !currentModel.isEmpty() && !"unknown".equals(currentModel)) {
                    customModelPref.setText(currentModel);
                    customModelPref.setVisible(true);
                }
                customModelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelName((String) newVal);
                    HermesConfigManager.restartGatewayIfRunning(requireContext());
                    return true;
                });
            }

            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref != null) {
                baseUrlPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setEnvVar("OPENAI_BASE_URL", (String) newVal);
                    HermesConfigManager.restartGatewayIfRunning(requireContext());
                    return true;
                });
                boolean needsUrl = "ollama".equals(currentProvider) || "custom".equals(currentProvider);
                baseUrlPref.setVisible(needsUrl);
            }

            // Temperature parameter
            Preference tempPref = findPreference("llm_temperature");
            if (tempPref != null) {
                float currentTemp = mConfigManager.getModelTemperature();
                tempPref.setSummary(getString(R.string.llm_temperature_summary) + "  Current: " + currentTemp);
                tempPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        float val = Float.parseFloat((String) newVal);
                        if (val < 0f || val > 2f) {
                            Toast.makeText(requireContext(), R.string.llm_temperature_invalid, Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        mConfigManager.setModelTemperature(val);
                        p.setSummary(getString(R.string.llm_temperature_summary) + "  Current: " + val);
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(), R.string.llm_temperature_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    return true;
                });
            }

            // Max tokens parameter
            Preference maxTokensPref = findPreference("llm_max_tokens");
            if (maxTokensPref != null) {
                int currentMax = mConfigManager.getModelMaxTokens();
                maxTokensPref.setSummary(getString(R.string.llm_max_tokens_summary) + "  Current: " + currentMax);
                maxTokensPref.setOnPreferenceChangeListener((p, newVal) -> {
                    try {
                        int val = Integer.parseInt((String) newVal);
                        if (val < 256 || val > 32768) {
                            Toast.makeText(requireContext(), R.string.llm_max_tokens_invalid, Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        mConfigManager.setModelMaxTokens(val);
                        p.setSummary(getString(R.string.llm_max_tokens_summary) + "  Current: " + val);
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(), R.string.llm_max_tokens_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    return true;
                });
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
                // Set to first preset (skip custom option)
                for (CharSequence m : models) {
                    if (!getString(R.string.llm_model_custom).equals(m.toString())) {
                        modelPref.setValue(m.toString());
                        mConfigManager.setModelName(m.toString());
                        break;
                    }
                }
            }
            Preference customModelPref = findPreference("llm_custom_model");
            if (customModelPref != null) customModelPref.setVisible(false);
        }

        private boolean isModelInPresetList(String provider, String modelName) {
            int arrayResId = getModelArrayResId(provider);
            if (arrayResId == 0) return false;
            CharSequence[] models = getResources().getTextArray(arrayResId);
            String customLabel = getString(R.string.llm_model_custom);
            for (CharSequence m : models) {
                if (m.toString().equals(modelName) && !m.toString().equals(customLabel)) {
                    return true;
                }
            }
            return false;
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

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            if ("llm_test_connection".equals(preference.getKey())) {
                testConnection(preference);
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

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_gateway_preferences, rootKey);
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
    }
}
