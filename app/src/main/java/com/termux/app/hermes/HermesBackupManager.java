package com.termux.app.hermes;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class HermesBackupManager {

    private static final String PREF_BACKUP_ENABLED = "backup_schedule_enabled";
    private static final String PREF_BACKUP_FREQUENCY = "backup_frequency";
    private static final String PREF_KEEP_COUNT = "backup_keep_count";
    private static final String PREF_LAST_BACKUP_TIME = "last_backup_time";
    private static final String BACKUP_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/backups";
    private static final String BACKUP_PREFIX = "hermes-config-";

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_BACKUP_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(PREF_BACKUP_ENABLED, enabled).apply();
    }

    public static String getFrequency(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_BACKUP_FREQUENCY, "daily");
    }

    public static int getKeepCount(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PREF_KEEP_COUNT, 5);
    }

    public static void setKeepCount(Context context, int count) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putInt(PREF_KEEP_COUNT, count).apply();
    }

    public static long getLastBackupTime(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(PREF_LAST_BACKUP_TIME, 0);
    }

    public static boolean shouldBackupNow(Context context) {
        if (!isEnabled(context)) return false;
        long last = getLastBackupTime(context);
        long now = System.currentTimeMillis();
        String freq = getFrequency(context);
        long interval;
        switch (freq) {
            case "weekly":  interval = 7 * 24 * 3600 * 1000L; break;
            case "monthly": interval = 30 * 24 * 3600 * 1000L; break;
            default:        interval = 24 * 3600 * 1000L; break;
        }
        return (now - last) >= interval;
    }

    public static String createBackup() throws Exception {
        File backupDir = new File(BACKUP_DIR);
        backupDir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String backupPath = BACKUP_DIR + "/" + BACKUP_PREFIX + timestamp + ".json";

        StringBuilder json = new StringBuilder();
        json.append("{
");

        // Read config.yaml
        String yamlPath = HermesConfigManager.CONFIG_YAML_PATH;
        File yamlFile = new File(yamlPath);
        if (yamlFile.exists()) {
            json.append("  "config_yaml": "");
            json.append(escapeJson(readFile(yamlPath)));
            json.append("",
");
        }

        // Read .env
        String envPath = HermesConfigManager.ENV_FILE_PATH;
        File envFile = new File(envPath);
        if (envFile.exists()) {
            json.append("  "env": "");
            json.append(escapeJson(readFile(envPath)));
            json.append(""
");
        }

        json.append("}");

        writeStringToFile(backupPath, json.toString());
        cleanupOldBackups(backupDir);

        return backupPath;
    }

    public static String[] listBackups() {
        File backupDir = new File(BACKUP_DIR);
        if (!backupDir.exists()) return new String[0];
        String[] files = backupDir.list((dir, name) -> name.startsWith(BACKUP_PREFIX) && name.endsWith(".json"));
        if (files == null) return new String[0];
        Arrays.sort(files);
        return files;
    }

    public static void restoreBackup(String backupName) throws Exception {
        String backupPath = BACKUP_DIR + "/" + backupName;
        String json = readFile(backupPath);

        String yaml = extractField(json, "config_yaml");
        String env = extractField(json, "env");

        if (yaml != null) writeStringToFile(HermesConfigManager.CONFIG_YAML_PATH, yaml);
        if (env != null) writeStringToFile(HermesConfigManager.ENV_FILE_PATH, env);

        HermesConfigManager.reinitialize();
    }

    private static void cleanupOldBackups(File backupDir) {
        String[] files = backupDir.list((dir, name) -> name.startsWith(BACKUP_PREFIX) && name.endsWith(".json"));
        if (files == null || files.length <= 5) return;
        Arrays.sort(files);
        for (int i = 0; i < files.length - 5; i++) {
            new File(backupDir, files[i]).delete();
        }
    }

    private static String readFile(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static void writeStringToFile(String path, String content) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            writer.write(content);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String extractField(String json, String field) {
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
                if (next == '\\"') { sb.append('"'); i++; continue; }
                if (next == '\\') { sb.append('\\'); i++; continue; }
            } else if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                return sb.toString();
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
