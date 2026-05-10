package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class HermesConfigExport {

    private static final String EXPORT_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/exports";

    public static String exportConfig(boolean includeSecrets) throws Exception {
        HermesConfigManager config = HermesConfigManager.getInstance();
        JSONObject json = new JSONObject();

        // LLM settings
        JSONObject llm = new JSONObject();
        llm.put("provider", config.getModelProvider());
        llm.put("model", config.getModelName());
        llm.put("base_url", config.getEnvVar("OPENAI_BASE_URL"));
        llm.put("temperature", config.getEnvVar("LLM_TEMPERATURE"));
        llm.put("max_tokens", config.getEnvVar("LLM_MAX_TOKENS"));
        llm.put("system_prompt", config.getSystemPrompt());
        if (includeSecrets) {
            llm.put("api_key", config.getApiKey(config.getModelProvider()));
        }
        json.put("llm", llm);

        // IM settings
        JSONObject im = new JSONObject();
        String tgToken = config.getEnvVar("TELEGRAM_BOT_TOKEN");
        String tgUsers = config.getEnvVar("TELEGRAM_ALLOWED_USERS");
        String dcToken = config.getEnvVar("DISCORD_BOT_TOKEN");
        String dcUsers = config.getEnvVar("DISCORD_ALLOWED_USERS");

        if (!tgToken.isEmpty()) {
            JSONObject tg = new JSONObject();
            if (includeSecrets) tg.put("bot_token", tgToken);
            tg.put("allowed_users", tgUsers);
            im.put("telegram", tg);
        }
        if (!dcToken.isEmpty()) {
            JSONObject dc = new JSONObject();
            if (includeSecrets) dc.put("bot_token", dcToken);
            dc.put("allowed_users", dcUsers);
            im.put("discord", dc);
        }

        // Feishu
        String fsAppId = config.getEnvVar("FEISHU_APP_ID");
        String fsSecret = config.getEnvVar("FEISHU_APP_SECRET");
        if (!fsAppId.isEmpty()) {
            JSONObject fs = new JSONObject();
            fs.put("app_id", fsAppId);
            if (includeSecrets) fs.put("app_secret", fsSecret);
            im.put("feishu", fs);
        }
        json.put("im", im);

        // Gateway settings
        JSONObject gateway = new JSONObject();
        gateway.put("auto_restart", config.getEnvVar("GATEWAY_AUTO_RESTART"));
        gateway.put("max_restarts", config.getEnvVar("GATEWAY_MAX_RESTARTS"));
        gateway.put("restart_delay", config.getEnvVar("GATEWAY_RESTART_DELAY"));
        json.put("gateway", gateway);

        json.put("version", 1);
        json.put("export_time", System.currentTimeMillis());

        // Write to file
        File dir = new File(EXPORT_DIR);
        dir.mkdirs();
        String filename = "hermes-config-" + System.currentTimeMillis() + ".json";
        String path = EXPORT_DIR + "/" + filename;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            writer.write(json.toString(2));
        }
        return path;
    }

    public static void importConfig(String jsonContent) throws Exception {
        JSONObject json = new JSONObject(jsonContent);
        HermesConfigManager config = HermesConfigManager.getInstance();

        if (json.has("llm")) {
            JSONObject llm = json.getJSONObject("llm");
            if (llm.has("provider")) config.setModelProvider(llm.getString("provider"));
            if (llm.has("model")) config.setModelName(llm.getString("model"));
            if (llm.has("base_url")) config.setEnvVar("OPENAI_BASE_URL", llm.getString("base_url"));
            if (llm.has("temperature")) config.setEnvVar("LLM_TEMPERATURE", llm.getString("temperature"));
            if (llm.has("max_tokens")) config.setEnvVar("LLM_MAX_TOKENS", llm.getString("max_tokens"));
            if (llm.has("system_prompt")) config.setSystemPrompt(llm.getString("system_prompt"));
            if (llm.has("api_key")) config.setApiKey(llm.getString("provider"), llm.getString("api_key"));
        }

        if (json.has("im")) {
            JSONObject im = json.getJSONObject("im");
            if (im.has("telegram")) {
                JSONObject tg = im.getJSONObject("telegram");
                if (tg.has("bot_token")) config.setEnvVar("TELEGRAM_BOT_TOKEN", tg.getString("bot_token"));
                if (tg.has("allowed_users")) config.setEnvVar("TELEGRAM_ALLOWED_USERS", tg.getString("allowed_users"));
            }
            if (im.has("discord")) {
                JSONObject dc = im.getJSONObject("discord");
                if (dc.has("bot_token")) config.setEnvVar("DISCORD_BOT_TOKEN", dc.getString("bot_token"));
                if (dc.has("allowed_users")) config.setEnvVar("DISCORD_ALLOWED_USERS", dc.getString("allowed_users"));
            }
            if (im.has("feishu")) {
                JSONObject fs = im.getJSONObject("feishu");
                if (fs.has("app_id")) config.setEnvVar("FEISHU_APP_ID", fs.getString("app_id"));
                if (fs.has("app_secret")) config.setEnvVar("FEISHU_APP_SECRET", fs.getString("app_secret"));
            }
        }

        if (json.has("gateway")) {
            JSONObject gw = json.getJSONObject("gateway");
            if (gw.has("auto_restart")) config.setEnvVar("GATEWAY_AUTO_RESTART", gw.getString("auto_restart"));
            if (gw.has("max_restarts")) config.setEnvVar("GATEWAY_MAX_RESTARTS", gw.getString("max_restarts"));
            if (gw.has("restart_delay")) config.setEnvVar("GATEWAY_RESTART_DELAY", gw.getString("restart_delay"));
        }
    }

    public static String readExportedConfig(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public static Intent createShareIntent(Context context, String filePath) {
        File file = new File(filePath);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/json");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Hermes Configuration");
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Hermes configuration file attached.");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(shareIntent, "Share Hermes Config");
    }
}
