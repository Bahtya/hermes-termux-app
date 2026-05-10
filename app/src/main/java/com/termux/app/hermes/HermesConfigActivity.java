package com.termux.app.hermes;

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
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

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
                case "hermes_gateway_control":
                    showFragment(new GatewayControlFragment());
                    return true;
                case "hermes_reset_config":
                    showResetConfirmDialog();
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
    }

    public static class LlmConfigFragment extends PreferenceFragmentCompat {

        private HermesConfigManager mConfigManager;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.hermes_llm_preferences, rootKey);
            mConfigManager = HermesConfigManager.getInstance();

            Preference apiKeyPref = findPreference("llm_api_key");
            if (apiKeyPref != null) {
                String currentKey = mConfigManager.getApiKey(mConfigManager.getModelProvider());
                if (currentKey != null && !currentKey.isEmpty()) {
                    apiKeyPref.setSummary(maskApiKey(currentKey));
                }
                apiKeyPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setApiKey(mConfigManager.getModelProvider(), (String) newVal);
                    p.setSummary(maskApiKey((String) newVal));
                    return true;
                });
            }

            Preference providerPref = findPreference("llm_provider");
            if (providerPref != null) {
                providerPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelProvider((String) newVal);
                    String key = mConfigManager.getApiKey((String) newVal);
                    Preference akp = findPreference("llm_api_key");
                    if (akp != null) {
                        akp.setSummary(key != null ? maskApiKey(key) : "");
                    }
                    return true;
                });
            }

            Preference modelPref = findPreference("llm_model");
            if (modelPref != null) {
                modelPref.setOnPreferenceChangeListener((p, newVal) -> {
                    mConfigManager.setModelName((String) newVal);
                    return true;
                });
            }
        }

        private String maskApiKey(String key) {
            if (key == null || key.length() < 8) return "****";
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
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
            String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            String hermesPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";

            String cmd;
            switch (action) {
                case "start":
                    cmd = "nohup " + hermesPath + " gateway run > ~/.hermes/logs/gateway.log 2>&1 &";
                    break;
                case "stop":
                    cmd = "pkill -f 'hermes gateway' 2>/dev/null; echo 'Gateway stopped'";
                    break;
                case "restart":
                    cmd = "pkill -f 'hermes gateway' 2>/dev/null; sleep 1; nohup " + hermesPath + " gateway run > ~/.hermes/logs/gateway.log 2>&1 &";
                    break;
                default:
                    return;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(bashPath, "-c", cmd);
                pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                        + ":/system/bin:/system/xbin");
                pb.start();

                int msgId = action.equals("start") ? R.string.gateway_started
                        : action.equals("stop") ? R.string.gateway_stopped
                        : R.string.gateway_restarted;
                Toast.makeText(requireContext(), msgId, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
