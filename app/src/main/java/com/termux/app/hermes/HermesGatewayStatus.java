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

    public static class ResourceInfo {
        public final int memRssMb;
        public final float cpuPercent;

        public ResourceInfo(int memRssMb, float cpuPercent) {
            this.memRssMb = memRssMb;
            this.cpuPercent = cpuPercent;
        }
    }

    public interface ResourceCallback {
        void onResult(ResourceInfo info);
    }

    public static void queryResourceUsageAsync(ResourceCallback callback) {
        new Thread(() -> {
            try {
                String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
                ProcessBuilder pb = new ProcessBuilder(bashPath, "-c",
                        "PID=$(pgrep -f 'hermes gateway' | head -1); "
                        + "if [ -n \"$PID\" ]; then "
                        + "  RSS=$(grep VmRSS /proc/$PID/status 2>/dev/null | awk '{print $2}'); "
                        + "  echo \"${RSS:-0}\"; "
                        + "fi");
                pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                        + ":/system/bin:/system/xbin");
                pb.redirectErrorStream(true);

                Process p = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String rssLine = reader.readLine();
                p.waitFor();

                int rssKb = 0;
                try {
                    rssKb = Integer.parseInt(rssLine != null ? rssLine.trim() : "0");
                } catch (NumberFormatException ignored) {}

                int rssMb = rssKb / 1024;
                callback.onResult(new ResourceInfo(rssMb, 0));
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to query resource usage: " + e.getMessage());
                callback.onResult(new ResourceInfo(0, 0));
            }
        }, "HermesResourceQuery").start();
    }
}
