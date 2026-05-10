package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
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
        private ActivityResultLauncher<String> mNotificationPermLauncher;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mNotificationPermLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), isGranted -> {
                        updateNotificationPermStatus();
                    });
        }

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

            // Notification permission status
            updateNotificationPermStatus();

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
                case "hermes_notification_perm":
                    requestNotificationPermission();
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

        private void updateNotificationPermStatus() {
            Preference permPref = findPreference("hermes_notification_perm");
            if (permPref == null) return;

            if (Build.VERSION.SDK_INT < 33) {
                permPref.setVisible(false);
                return;
            }

            boolean granted = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            permPref.setSummary(granted
                    ? getString(R.string.notification_perm_granted)
                    : getString(R.string.notification_perm_denied));
        }

        private void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT < 33) return;

            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), R.string.notification_perm_granted, Toast.LENGTH_SHORT).show();
                return;
            }

            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.notification_perm_rationale_title)
                        .setMessage(R.string.notification_perm_rationale_message)
                        .setPositiveButton(android.R.string.ok, (d, w) ->
                                mNotificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                mNotificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
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

        // Provider capabilities
        private static final String[] PROVIDERS_NEEDING_API_KEY = {
            "openai", "anthropic", "google", "deepseek", "openrouter",
            "xai", "alibaba", "mistral", "nvidia", "custom"
        };
        private static final String[] PROVIDERS_NEEDING_BASE_URL = {"ollama", "custom"};

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_llm_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            String currentProvider = mConfigManager.getModelProvider();
            updateModelList(currentProvider);
            applyProviderConfig(currentProvider);

            setupProviderSelector();
            setupApiKeyField();
            setupModelSelector();
            setupCustomModelField();
            setupBaseUrlField();
        }

        private void setupProviderSelector() {
            Preference providerPref = findPreference("llm_provider");
            if (providerPref == null) return;

            providerPref.setOnPreferenceChangeListener((p, newVal) -> {
                String provider = (String) newVal;
                mConfigManager.setModelProvider(provider);
                updateModelList(provider);
                applyProviderConfig(provider);
                return true;
            });
        }

        private void setupApiKeyField() {
            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref == null) return;

            String provider = mConfigManager.getModelProvider();
            String currentKey = mConfigManager.getApiKey(provider);
            if (!currentKey.isEmpty()) {
                apiKeyPref.setSummary(maskApiKey(currentKey));
            }

            apiKeyPref.setOnPreferenceChangeListener((p, newVal) -> {
                String key = (String) newVal;
                String currentProvider = mConfigManager.getModelProvider();
                mConfigManager.setApiKey(currentProvider, key);
                p.setSummary(maskApiKey(key));

                if (shouldValidateApiKey(currentProvider) && !key.isEmpty()) {
                    if (!isValidApiKeyFormat(currentProvider, key)) {
                        Preference hint = findPreference("llm_api_key_hint");
                        if (hint != null) {
                            hint.setSummary(getString(R.string.llm_api_key_format_warn));
                        }
                    }
                }
                return true;
            });
        }

        private void setupModelSelector() {
            ListPreference modelPref = findPreference("llm_model");
            if (modelPref == null) return;

            modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                String model = (String) newVal;
                mConfigManager.setModelName(model);
                Preference customModelPref = findPreference("llm_custom_model");
                if (customModelPref != null) {
                    customModelPref.setVisible(false);
                }
                return true;
            });
        }

        private void setupCustomModelField() {
            Preference customModelPref = findPreference("llm_custom_model");
            if (customModelPref == null) return;

            customModelPref.setOnPreferenceChangeListener((p, newVal) -> {
                String model = (String) newVal;
                if (!model.trim().isEmpty()) {
                    mConfigManager.setModelName(model.trim());
                }
                return true;
            });
        }

        private void setupBaseUrlField() {
            Preference baseUrlPref = findPreference("llm_base_url");
            if (baseUrlPref == null) return;

            baseUrlPref.setOnPreferenceChangeListener((p, newVal) -> {
                String url = (String) newVal;
                mConfigManager.setEnvVar("OPENAI_BASE_URL", url);
                return true;
            });
        }

        /** Apply provider-specific UI configuration: field visibility, hints, defaults. */
        private void applyProviderConfig(String provider) {
            boolean needsApiKey = needsApiKey(provider);
            boolean needsBaseUrl = needsBaseUrl(provider);

            // Auth category visibility
            Preference authCategory = findPreference("llm_category_auth");
            if (authCategory != null) authCategory.setVisible(needsApiKey);

            // API key hint
            Preference apiKeyHint = findPreference("llm_api_key_hint");
            if (apiKeyHint != null) {
                apiKeyHint.setSummary(getApiKeyHintResId(provider));
                if (!needsApiKey) {
                    apiKeyHint.setSummary(getString(R.string.llm_api_key_not_needed));
                }
            }

            // Update API key summary with current provider's key
            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref != null) {
                String key = mConfigManager.getApiKey(provider);
                apiKeyPref.setSummary(key.isEmpty() ? "" : maskApiKey(key));
            }

            // Endpoint category visibility
            Preference endpointCategory = findPreference("llm_category_endpoint");
            if (endpointCategory != null) endpointCategory.setVisible(needsBaseUrl);

            // Base URL hint
            Preference baseUrlHint = findPreference("llm_base_url_hint");
            if (baseUrlHint != null) {
                if ("ollama".equals(provider)) {
                    String currentUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
                    if (currentUrl.isEmpty()) {
                        baseUrlHint.setSummary(getString(R.string.llm_base_url_hint_ollama));
                    } else {
                        baseUrlHint.setSummary(getString(R.string.llm_base_url_auto_set, currentUrl));
                    }
                } else if ("custom".equals(provider)) {
                    baseUrlHint.setSummary(getString(R.string.llm_base_url_hint_custom));
                }
            }

            // Auto-fill base URL for Ollama if empty
            if ("ollama".equals(provider)) {
                String currentUrl = mConfigManager.getEnvVar("OPENAI_BASE_URL");
                if (currentUrl.isEmpty()) {
                    mConfigManager.setEnvVar("OPENAI_BASE_URL", "http://localhost:11434");
                    Preference baseUrlPref = findPreference("llm_base_url");
                    if (baseUrlPref instanceof androidx.preference.EditTextPreference) {
                        ((androidx.preference.EditTextPreference) baseUrlPref).setText("http://localhost:11434");
                    }
                }
            }

            // Provider info
            Preference providerInfo = findPreference("llm_provider_info");
            if (providerInfo != null) {
                providerInfo.setSummary(getProviderInfoResId(provider));
            }
        }

        private boolean needsApiKey(String provider) {
            for (String p : PROVIDERS_NEEDING_API_KEY) {
                if (p.equals(provider)) return true;
            }
            return false;
        }

        private boolean needsBaseUrl(String provider) {
            for (String p : PROVIDERS_NEEDING_BASE_URL) {
                if (p.equals(provider)) return true;
            }
            return false;
        }

        private String getProviderInfoResId(String provider) {
            switch (provider) {
                case "openai": return getString(R.string.llm_provider_info_openai);
                case "anthropic": return getString(R.string.llm_provider_info_anthropic);
                case "google": return getString(R.string.llm_provider_info_google);
                case "deepseek": return getString(R.string.llm_provider_info_deepseek);
                case "openrouter": return getString(R.string.llm_provider_info_openrouter);
                case "xai": return getString(R.string.llm_provider_info_xai);
                case "alibaba": return getString(R.string.llm_provider_info_alibaba);
                case "mistral": return getString(R.string.llm_provider_info_mistral);
                case "nvidia": return getString(R.string.llm_provider_info_nvidia);
                case "ollama": return getString(R.string.llm_provider_info_ollama);
                case "custom": return getString(R.string.llm_provider_info_custom);
                default: return "";
            }
        }

        private String getApiKeyHintResId(String provider) {
            switch (provider) {
                case "openai": return getString(R.string.llm_api_key_hint_openai);
                case "anthropic": return getString(R.string.llm_api_key_hint_anthropic);
                case "google": return getString(R.string.llm_api_key_hint_google);
                case "deepseek": return getString(R.string.llm_api_key_hint_deepseek);
                case "openrouter": return getString(R.string.llm_api_key_hint_openrouter);
                case "xai": return getString(R.string.llm_api_key_hint_xai);
                case "alibaba": return getString(R.string.llm_api_key_hint_alibaba);
                case "mistral": return getString(R.string.llm_api_key_hint_mistral);
                case "nvidia": return getString(R.string.llm_api_key_hint_nvidia);
                case "ollama": return getString(R.string.llm_api_key_hint_ollama);
                case "custom": return getString(R.string.llm_api_key_hint_custom);
                default: return "";
            }
        }

        private boolean shouldValidateApiKey(String provider) {
            return "openai".equals(provider) || "anthropic".equals(provider)
                    || "google".equals(provider) || "deepseek".equals(provider);
        }

        private boolean isValidApiKeyFormat(String provider, String key) {
            if (key == null || key.isEmpty()) return false;
            switch (provider) {
                case "openai":
                    return key.startsWith("sk-") && key.length() > 20;
                case "anthropic":
                    return key.startsWith("sk-ant-") && key.length() > 20;
                case "google":
                    return key.length() >= 30;
                case "deepseek":
                    return key.startsWith("sk-") && key.length() > 10;
                default:
                    return true;
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
                modelPref.setVisible(true);

                // Set the saved model or default to first
                String savedModel = mConfigManager.getModelName();
                boolean modelInList = false;
                for (CharSequence m : models) {
                    if (m.toString().equals(savedModel)) {
                        modelInList = true;
                        break;
                    }
                }
                if (modelInList) {
                    modelPref.setValue(savedModel);
                } else if (models.length > 0) {
                    modelPref.setValue(models[0].toString());
                    mConfigManager.setModelName(models[0].toString());
                }

                // Show custom model field if saved model is not in the list
                Preference customModelPref = findPreference("llm_custom_model");
                if (customModelPref != null) {
                    if (!modelInList && !savedModel.isEmpty()) {
                        customModelPref.setVisible(true);
                        if (customModelPref instanceof androidx.preference.EditTextPreference) {
                            ((androidx.preference.EditTextPreference) customModelPref).setText(savedModel);
                        }
                    } else {
                        customModelPref.setVisible(false);
                    }
                }
            } else {
                modelPref.setVisible(false);
                Preference customModelPref = findPreference("llm_custom_model");
                if (customModelPref != null) {
                    customModelPref.setVisible(true);
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
            } else if (apiKey.isEmpty() && needsApiKey(provider)) {
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
