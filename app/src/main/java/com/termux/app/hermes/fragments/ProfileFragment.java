package com.termux.app.hermes.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.hermes.GatewayLogActivity;
import com.termux.app.hermes.HermesConfigActivity;
import com.termux.app.hermes.HermesConfigManager;
import com.termux.app.hermes.HermesDiagnosticActivity;
import com.termux.app.hermes.HermesGatewayService;
import com.termux.app.hermes.HermesGatewayStatus;
import com.termux.app.hermes.HermesHelpActivity;
import com.termux.app.hermes.HermesInstallActivity;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import com.termux.shared.termux.TermuxConstants;

public class ProfileFragment extends Fragment {

    private static final int REQUEST_IMPORT_CONFIG = 1001;

    private TextView mGatewayStatusText;
    private Button mGatewayToggleButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Gateway status card
        mGatewayStatusText = view.findViewById(R.id.gateway_status_text);
        mGatewayToggleButton = view.findViewById(R.id.btn_gateway_toggle);

        if (mGatewayToggleButton != null) {
            mGatewayToggleButton.setOnClickListener(v -> toggleGateway());
        }

        setupNavigationAction(view, R.id.btn_ai_config,
                new Intent(requireContext(), HermesConfigActivity.class)
                        .putExtra(HermesConfigActivity.EXTRA_NAV_SECTION, R.id.nav_ai_config));
        setupNavigationAction(view, R.id.btn_im_setup,
                new Intent(requireContext(), HermesConfigActivity.class)
                        .putExtra(HermesConfigActivity.EXTRA_NAV_SECTION, R.id.nav_im_setup));
        setupNavigationAction(view, R.id.btn_logs,
                new Intent(requireContext(), GatewayLogActivity.class));
        setupNavigationAction(view, R.id.btn_diagnostics,
                new Intent(requireContext(), HermesDiagnosticActivity.class));

        // About card
        TextView versionText = view.findViewById(R.id.version_text);
        if (versionText != null) {
            versionText.setText(getVersionDisplay());
        }

        Button helpButton = view.findViewById(R.id.btn_help);
        if (helpButton != null) {
            helpButton.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), HermesHelpActivity.class)));
        }

        // Backup/export/import buttons
        Button exportBtn = view.findViewById(R.id.btn_export_config);
        if (exportBtn != null) exportBtn.setOnClickListener(v -> exportConfig());

        Button importBtn = view.findViewById(R.id.btn_import_config);
        if (importBtn != null) importBtn.setOnClickListener(v -> openConfigPicker());

        Button restoreBtn = view.findViewById(R.id.btn_restore_backup);
        if (restoreBtn != null) restoreBtn.setOnClickListener(v -> confirmRestoreBackup());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshGatewayStatus();
    }

    private void refreshGatewayStatus() {
        HermesGatewayStatus.checkAsync((status, detail) -> {
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> updateGatewayStatusUI(status));
        });
    }

    private void updateGatewayStatusUI(HermesGatewayStatus.Status status) {
        if (mGatewayStatusText == null || mGatewayToggleButton == null) return;

        int colorRes;
        String statusText;
        String buttonText;

        switch (status) {
            case RUNNING:
                colorRes = R.color.hermes_status_running;
                statusText = getString(R.string.gateway_status_running);
                buttonText = getString(R.string.gateway_stop_title);
                break;
            case NOT_INSTALLED:
                colorRes = R.color.hermes_status_not_installed;
                statusText = getString(R.string.gateway_status_not_installed);
                buttonText = getString(R.string.install_action_install);
                break;
            default:
                colorRes = R.color.hermes_status_stopped;
                statusText = getString(R.string.gateway_status_stopped);
                buttonText = getString(R.string.gateway_start_title);
                break;
        }

        mGatewayStatusText.setText(statusText);
        mGatewayStatusText.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        mGatewayToggleButton.setText(buttonText);
    }

    private void toggleGateway() {
        HermesGatewayStatus.checkAsync((status, detail) -> {
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                switch (status) {
                    case RUNNING:
                        stopGateway();
                        break;
                    case NOT_INSTALLED:
                        // Open install activity
                        startActivity(new Intent(requireContext(), HermesInstallActivity.class));
                        break;
                    default:
                        startGateway();
                        break;
                }
            });
        });
    }

    private void startGateway() {
        String hermesPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";
        if (!new File(hermesPath).exists()) {
            Toast.makeText(requireContext(), R.string.gateway_status_not_installed,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Intent startIntent = new Intent(requireContext(), HermesGatewayService.class);
        startIntent.setAction(HermesGatewayService.ACTION_START);
        requireContext().startService(startIntent);
        Toast.makeText(requireContext(), R.string.gateway_started, Toast.LENGTH_SHORT).show();

        View view = getView();
        if (view != null) view.postDelayed(this::refreshGatewayStatus, 2000);
    }

    private void stopGateway() {
        Intent stopIntent = new Intent(requireContext(), HermesGatewayService.class);
        stopIntent.setAction(HermesGatewayService.ACTION_STOP);
        requireContext().startService(stopIntent);
        Toast.makeText(requireContext(), R.string.gateway_stopped, Toast.LENGTH_SHORT).show();

        View view = getView();
        if (view != null) view.postDelayed(this::refreshGatewayStatus, 2000);
    }

    private void setupNavigationAction(View parent, int buttonId, Intent intent) {
        Button btn = parent.findViewById(buttonId);
        if (btn == null) return;
        btn.setOnClickListener(v -> startActivity(intent));
    }

    private String getVersionDisplay() {
        try {
            String hermesPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";
            if (new File(hermesPath).exists()) {
                return getString(R.string.profile_installed);
            } else {
                return getString(R.string.profile_not_installed);
            }
        } catch (Exception e) {
            return getString(R.string.profile_version_unavailable);
        }
    }

    private void exportConfig() {
        new Thread(() -> {
            HermesConfigManager mgr = HermesConfigManager.getInstance();
            String json = mgr.exportConfigMasked();
            File exportDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".hermes");
            if (!exportDir.exists()) exportDir.mkdirs();
            File file = new File(exportDir, "hermes_config_export.json");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(json);
            } catch (Exception e) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }

            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                try {
                    Uri uri = FileProvider.getUriForFile(requireContext(),
                            requireContext().getPackageName() + ".fileprovider", file);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/json");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, getString(R.string.profile_export_config)));
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            getString(R.string.profile_config_exported) + ": " + file.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void openConfigPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CONFIG);
    }

    private void confirmRestoreBackup() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_restore_confirm_title)
                .setMessage(R.string.profile_restore_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    boolean ok = HermesConfigManager.getInstance().restoreFromBackup();
                    if (ok) {
                        Toast.makeText(requireContext(), R.string.profile_backup_restored,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), R.string.profile_no_backup,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_IMPORT_CONFIG && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            importConfigFromUri(data.getData());
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void importConfigFromUri(Uri uri) {
        new Thread(() -> {
            try {
                java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
                if (is == null) return;
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                boolean ok = HermesConfigManager.getInstance().importConfig(sb.toString());
                if (getActivity() == null) return;
                int msg = ok ? R.string.profile_config_imported : R.string.profile_config_import_failed;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), R.string.profile_config_import_failed,
                                Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
