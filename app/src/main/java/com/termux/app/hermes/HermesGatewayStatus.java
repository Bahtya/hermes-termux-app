package com.termux.app.hermes;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class HermesGatewayStatus {

    private static final String LOG_TAG = "HermesGatewayStatus";

    public enum Status {
        RUNNING,
        STOPPED,
        NOT_INSTALLED
    }

    public interface StatusCallback {
        void onResult(Status status, String detail);
    }

    public static void checkAsync(StatusCallback callback) {
        new Thread(() -> {
            Status status;
            String detail = "";

            String hermesPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/hermes";
            if (!new File(hermesPath).exists()) {
                status = Status.NOT_INSTALLED;
                detail = "Hermes CLI not found at " + hermesPath;
            } else if (isGatewayRunning()) {
                status = Status.RUNNING;
                detail = "Gateway is running";
            } else {
                status = Status.STOPPED;
                detail = "Gateway is stopped";
            }

            Status finalStatus = status;
            String finalDetail = detail;
            callback.onResult(finalStatus, finalDetail);
        }, "HermesStatusCheck").start();
    }

    private static boolean isGatewayRunning() {
        try {
            String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            if (!new File(bashPath).exists()) return false;

            ProcessBuilder pb = new ProcessBuilder(bashPath, "-c",
                    "pgrep -f 'hermes gateway' >/dev/null 2>&1 && echo RUNNING || echo STOPPED");
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                    + ":/system/bin:/system/xbin");
            pb.redirectErrorStream(true);

            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            return "RUNNING".equals(line);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to check gateway status: " + e.getMessage());
            return false;
        }
    }
}
