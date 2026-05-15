package com.termux.app.hermes.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.hermes.HermesGatewayService;
import com.termux.app.hermes.HermesGatewayStatus;
import com.termux.app.hermes.HermesInstallActivity;

import java.io.File;

import com.termux.shared.termux.TermuxConstants;

public class ProfileFragment extends Fragment {

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

        setupQuickAction(view, R.id.btn_ai_config, getString(R.string.profile_ai_config));
        setupQuickAction(view, R.id.btn_im_setup, getString(R.string.profile_im_setup));
        setupQuickAction(view, R.id.btn_logs, getString(R.string.profile_logs));
        setupQuickAction(view, R.id.btn_diagnostics, getString(R.string.profile_diagnostics));

        // About card
        TextView versionText = view.findViewById(R.id.version_text);
        if (versionText != null) {
            versionText.setText(getVersionDisplay());
        }

        Button helpButton = view.findViewById(R.id.btn_help);
        if (helpButton != null) {
            helpButton.setOnClickListener(v ->
                    Toast.makeText(requireContext(), R.string.profile_help, Toast.LENGTH_SHORT).show());
        }
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

    private void setupQuickAction(View parent, int buttonId, String label) {
        Button btn = parent.findViewById(buttonId);
        if (btn == null) return;

        btn.setOnClickListener(v ->
                Toast.makeText(requireContext(), label + " — coming soon", Toast.LENGTH_SHORT).show());
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
}
