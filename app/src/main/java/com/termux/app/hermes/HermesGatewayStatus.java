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

    public enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public interface HealthCallback {
        void onResult(HealthStatus status, long latencyMs, String error);
    }

    public static void checkHealthAsync(HealthCallback callback) {
        new Thread(() -> {
            String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            String curlPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/curl";
            if (!new File(bashPath).exists() || !new File(curlPath).exists()) {
                callback.onResult(HealthStatus.UNHEALTHY, 0, "bash or curl not available");
                return;
            }

            try {
                long start = System.currentTimeMillis();
                ProcessBuilder pb = new ProcessBuilder(curlPath, "-s", "-o", "/dev/null",
                        "-w", "%{http_code}", "--connect-timeout", "5", "--max-time", "10",
                        "http://127.0.0.1:8080/health");
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                        + ":/system/bin:/system/xbin");
                pb.redirectErrorStream(true);

                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String output = reader.readLine();
                p.waitFor();
                long latency = System.currentTimeMillis() - start;

                int httpCode = 0;
                try {
                    httpCode = Integer.parseInt(output != null ? output.trim() : "0");
                } catch (NumberFormatException ignored) {}

                if (httpCode == 200) {
                    HealthStatus status = latency < 1000 ? HealthStatus.HEALTHY : HealthStatus.DEGRADED;
                    callback.onResult(status, latency, null);
                } else if (httpCode > 0) {
                    callback.onResult(HealthStatus.UNHEALTHY, latency, "HTTP " + httpCode);
                } else {
                    callback.onResult(HealthStatus.UNHEALTHY, latency, "No response");
                }
            } catch (Exception e) {
                callback.onResult(HealthStatus.UNHEALTHY, 0, e.getMessage());
            }
        }, "HermesHealthCheck").start();
    }
}
