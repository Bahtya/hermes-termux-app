package com.termux.app.hermes;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Hermes Agent configuration files located at ~/.hermes/.
 * <p>
 * Handles reading and writing of:
 * <ul>
 *   <li>{@code config.yaml} - YAML configuration for terminal backend, model settings, and Feishu integration.</li>
 *   <li>{@code .env} - Key-value pairs for API keys and environment variables.</li>
 * </ul>
 * Creates default config files if they do not exist.
 */
public class HermesConfigManager {

    private static final String LOG_TAG = "HermesConfigManager";

    private static final String HERMES_DIR_NAME = ".hermes";
    private static final String CONFIG_YAML_FILE = "config.yaml";
    private static final String ENV_FILE = ".env";

    /** Full path to the ~/.hermes/ directory. */
    public static final String HERMES_CONFIG_DIR_PATH =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + HERMES_DIR_NAME;

    /** Full path to config.yaml. */
    public static final String CONFIG_YAML_PATH =
            HERMES_CONFIG_DIR_PATH + "/" + CONFIG_YAML_FILE;

    /** Full path to .env. */
    public static final String ENV_FILE_PATH =
            HERMES_CONFIG_DIR_PATH + "/" + ENV_FILE;

    // ---- config.yaml keys ----
    private static final String KEY_TERMINAL_BACKEND = "terminal.backend";
    private static final String KEY_TERMINAL_CWD = "terminal.cwd";
    private static final String KEY_MODEL_PROVIDER = "model.provider";
    private static final String KEY_MODEL_NAME = "model.name";
    private static final String KEY_MODEL_TEMPERATURE = "model.temperature";
    private static final String KEY_MODEL_MAX_TOKENS = "model.max_tokens";
    private static final String KEY_MODEL_TOP_P = "model.top_p";
    private static final String KEY_MODEL_FREQUENCY_PENALTY = "model.frequency_penalty";
    private static final String KEY_MODEL_PRESENCE_PENALTY = "model.presence_penalty";
    private static final String KEY_MODEL_COMMAND_TIMEOUT = "model.command_timeout";
    private static final String KEY_SYSTEM_PROMPT = "model.system_prompt";

    // Feishu config.yaml keys (under feishu: section)
    private static final String KEY_FEISHU_APP_ID = "feishu.app_id";
    private static final String KEY_FEISHU_APP_SECRET = "feishu.app_secret";
    private static final String KEY_FEISHU_DOMAIN = "feishu.domain";
    private static final String KEY_FEISHU_CONNECTION_MODE = "feishu.connection_mode";
    private static final String KEY_FEISHU_ALLOWED_USERS = "feishu.allowed_users";
    private static final String KEY_FEISHU_HOME_CHANNEL = "feishu.home_channel";

    // Agent behavior config.yaml keys
    private static final String KEY_COMPRESSION_ENABLED = "compression.enabled";
    private static final String KEY_COMPRESSION_THRESHOLD = "compression.threshold";
    private static final String KEY_CONTEXT_LENGTH = "context_length";
    private static final String KEY_AGENT_MAX_TURNS = "agent.max_turns";
    private static final String KEY_AGENT_GATEWAY_TIMEOUT = "agent.gateway_timeout";
    private static final String KEY_AGENT_VERBOSE = "agent.verbose";
    private static final String KEY_MEMORY_ENABLED = "memory.memory_enabled";
    private static final String KEY_USER_PROFILE_ENABLED = "memory.user_profile_enabled";
    private static final String KEY_MEMORY_CHAR_LIMIT = "memory.memory_char_limit";
    private static final String KEY_SESSION_RESET_MODE = "session_reset.mode";
    private static final String KEY_SESSION_IDLE_MINUTES = "session_reset.idle_minutes";
    private static final String KEY_SESSION_RESET_HOUR = "session_reset.at_hour";

    // ---- .env key constants ----
    private static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";
    private static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    private static final String ENV_GOOGLE_API_KEY = "GOOGLE_API_KEY";
    private static final String ENV_DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY";
    private static final String ENV_OPENROUTER_API_KEY = "OPENROUTER_API_KEY";
    private static final String ENV_XAI_API_KEY = "XAI_API_KEY";
    private static final String ENV_ALIBABA_API_KEY = "DASHSCOPE_API_KEY";
    private static final String ENV_MISTRAL_API_KEY = "MISTRAL_API_KEY";
    private static final String ENV_NVIDIA_API_KEY = "NVIDIA_API_KEY";
    private static final String ENV_FEISHU_APP_ID = "FEISHU_APP_ID";
    private static final String ENV_FEISHU_APP_SECRET = "FEISHU_APP_SECRET";
    private static final String ENV_FEISHU_DOMAIN = "FEISHU_DOMAIN";
    private static final String ENV_FEISHU_CONNECTION_MODE = "FEISHU_CONNECTION_MODE";

    // Cached values
    private final Map<String, String> mYamlConfig = new LinkedHashMap<>();
    private final Map<String, String> mEnvVars = new LinkedHashMap<>();
    private boolean mLoaded = false;

    // Singleton
    private static volatile HermesConfigManager sInstance;

    /** Returns the singleton instance, loading config from disk on first access. */
    public static HermesConfigManager getInstance() {
        if (sInstance == null) {
            synchronized (HermesConfigManager.class) {
                if (sInstance == null) {
                    sInstance = new HermesConfigManager();
                }
            }
        }
        return sInstance;
    }

    private HermesConfigManager() {
        ensureDefaultConfig();
        loadConfig();
    }

    /** Call after hermes-agent installation completes to reinitialize config. */
    public static void reinitialize() {
        HermesConfigManager mgr = getInstance();
        mgr.ensureDefaultConfig();
        mgr.loadConfig();
    }

    // =========================================================================
    // Path helpers
    // =========================================================================

    /** Returns the Hermes config directory path: {@code ~/.hermes/}. */
    public String getConfigDir() {
        return HERMES_CONFIG_DIR_PATH;
    }

    /** Returns the full path to {@code config.yaml}. */
    public String getConfigYamlPath() {
        return CONFIG_YAML_PATH;
    }

    /** Returns the full path to the {@code .env} file. */
    public String getEnvFilePath() {
        return ENV_FILE_PATH;
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Ensures the {@code ~/.hermes/} directory exists and creates default
     * {@code config.yaml} and {@code .env} files when they are absent.
     */
    public synchronized void ensureDefaultConfig() {
        File dir = new File(HERMES_CONFIG_DIR_PATH);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                Logger.logInfo(LOG_TAG, "Created Hermes config directory: " + HERMES_CONFIG_DIR_PATH);
            } else {
                Logger.logError(LOG_TAG, "Failed to create Hermes config directory: " + HERMES_CONFIG_DIR_PATH);
                return;
            }
        }

        // Create default config.yaml if missing
        File yamlFile = new File(CONFIG_YAML_PATH);
        if (!yamlFile.exists()) {
            writeDefaultConfigYaml();
        }

        // Create default .env if missing
        File envFile = new File(ENV_FILE_PATH);
        if (!envFile.exists()) {
            writeDefaultEnvFile();
        }
    }

    private void writeDefaultConfigYaml() {
        String defaultYaml =
                "# Hermes Agent Configuration\n"
                + "terminal:\n"
                + "  backend: local\n"
                + "\n"
                + "model:\n"
                + "  provider: openai\n"
                + "  name: gpt-4o\n"
                + "  temperature: 0.7\n"
                + "  max_tokens: 4096\n"
                + "  top_p: 1.0\n"
                + "  frequency_penalty: 0.0\n"
                + "  presence_penalty: 0.0\n"
                + "\n"
                + "feishu:\n"
                + "  app_id: \"\"\n"
                + "  app_secret: \"\"\n"
                + "  domain: feishu\n"
                + "  connection_mode: websocket\n"
                + "  allowed_users: \"\"\n"
                + "  home_channel: \"\"\n";

        writeStringToFile(CONFIG_YAML_PATH, defaultYaml);
        Logger.logInfo(LOG_TAG, "Created default config.yaml");
    }

    private void writeDefaultEnvFile() {
        String defaultEnv =
                "# Hermes Agent Environment Variables\n"
                + "# LLM Provider API Keys\n"
                + "OPENAI_API_KEY=\n"
                + "ANTHROPIC_API_KEY=\n"
                + "GOOGLE_API_KEY=\n"
                + "DEEPSEEK_API_KEY=\n"
                + "OPENROUTER_API_KEY=\n"
                + "XAI_API_KEY=\n"
                + "DASHSCOPE_API_KEY=\n"
                + "MISTRAL_API_KEY=\n"
                + "NVIDIA_API_KEY=\n"
                + "OLLAMA_API_KEY=\n"
                + "# Custom OpenAI-compatible endpoint\n"
                + "OPENAI_BASE_URL=\n"
                + "\n"
                + "# Feishu Integration\n"
                + "FEISHU_APP_ID=\n"
                + "FEISHU_APP_SECRET=\n"
                + "FEISHU_DOMAIN=feishu\n"
                + "FEISHU_CONNECTION_MODE=websocket\n"
                + "\n"
                + "# Telegram Integration\n"
                + "TELEGRAM_BOT_TOKEN=\n"
                + "TELEGRAM_ALLOWED_USERS=\n"
                + "\n"
                + "# Discord Integration\n"
                + "DISCORD_BOT_TOKEN=\n"
                + "DISCORD_ALLOWED_USERS=\n";

        writeStringToFile(ENV_FILE_PATH, defaultEnv);
        Logger.logInfo(LOG_TAG, "Created default .env file");
    }

    // =========================================================================
    // Load / Reload
    // =========================================================================

    /** (Re)loads all configuration from disk. */
    public synchronized void loadConfig() {
        mYamlConfig.clear();
        mEnvVars.clear();
        parseYamlFile();
        parseEnvFile();
        mLoaded = true;
        Logger.logDebug(LOG_TAG, "Configuration loaded. YAML keys: " + mYamlConfig.size()
                + ", ENV keys: " + mEnvVars.size());
    }

    // =========================================================================
    // YAML parsing (simple string-based, no library dependency)
    // =========================================================================

    /**
     * Reads config.yaml and populates {@link #mYamlConfig} with flattened
     * dotted keys (e.g. {@code "terminal.backend"}).
     * <p>
     * Handles simple two-level nesting. Lines that are blank or start with
     * {@code #} are skipped.
     */
    private void parseYamlFile() {
        File file = new File(CONFIG_YAML_PATH);
        if (!file.exists()) {
            Logger.logWarn(LOG_TAG, "config.yaml not found at " + CONFIG_YAML_PATH);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String currentSection = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // Top-level section header (e.g. "terminal:")
                if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":")) {
                    currentSection = trimmed.substring(0, trimmed.length() - 1);
                    continue;
                }

                // Key-value pair (e.g. "  backend: local")
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx > 0) {
                    String key = trimmed.substring(0, colonIdx).trim();
                    String value = trimmed.substring(colonIdx + 1).trim();

                    // Strip surrounding quotes
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }

                    // Store as flattened dotted key
                    String flatKey = (currentSection != null) ? currentSection + "." + key : key;
                    mYamlConfig.put(flatKey, value);
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Error reading config.yaml: " + e.getMessage());
        }
    }

    /** Writes the current {@link #mYamlConfig} map back to config.yaml. */
    private synchronized void writeYamlConfig() {
        // Reconstruct a simple YAML from the flat map, grouping by section.
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        // Always include these sections in this order.
        sections.put("terminal", new LinkedHashMap<>());
        sections.put("model", new LinkedHashMap<>());
        sections.put("feishu", new LinkedHashMap<>());

        for (Map.Entry<String, String> entry : mYamlConfig.entrySet()) {
            String flatKey = entry.getKey();
            int dot = flatKey.indexOf('.');
            if (dot > 0) {
                String section = flatKey.substring(0, dot);
                String subKey = flatKey.substring(dot + 1);
                Map<String, String> secMap = sections.get(section);
                if (secMap == null) {
                    secMap = new LinkedHashMap<>();
                    sections.put(section, secMap);
                }
                secMap.put(subKey, entry.getValue());
            } else {
                // Top-level key without a section
                Map<String, String> root = sections.get("");
                if (root == null) {
                    root = new LinkedHashMap<>();
                    sections.put("", root);
                }
                root.put(flatKey, entry.getValue());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Hermes Agent Configuration\n");

        for (Map.Entry<String, Map<String, String>> secEntry : sections.entrySet()) {
            String section = secEntry.getKey();
            Map<String, String> values = secEntry.getValue();
            if (values.isEmpty()) continue;

            if (!section.isEmpty()) {
                sb.append("\n").append(section).append(":\n");
                for (Map.Entry<String, String> kv : values.entrySet()) {
                    String v = kv.getValue();
                    if (v == null) v = "";
                    // Quote the value if it contains special YAML characters or is empty
                    if (v.isEmpty() || v.contains(":") || v.contains("#") || v.contains(" ")) {
                        sb.append("  ").append(kv.getKey()).append(": \"").append(v).append("\"\n");
                    } else {
                        sb.append("  ").append(kv.getKey()).append(": ").append(v).append("\n");
                    }
                }
            } else {
                for (Map.Entry<String, String> kv : values.entrySet()) {
                    sb.append(kv.getKey()).append(": ").append(kv.getValue()).append("\n");
                }
            }
        }

        writeStringToFile(CONFIG_YAML_PATH, sb.toString());
        Logger.logDebug(LOG_TAG, "config.yaml written");
    }

    // =========================================================================
    // .env parsing
    // =========================================================================

    /** Reads the {@code .env} file and populates {@link #mEnvVars}. */
    private void parseEnvFile() {
        File file = new File(ENV_FILE_PATH);
        if (!file.exists()) {
            Logger.logWarn(LOG_TAG, ".env file not found at " + ENV_FILE_PATH);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx > 0) {
                    String key = trimmed.substring(0, eqIdx).trim();
                    String value = trimmed.substring(eqIdx + 1).trim();
                    // Strip surrounding quotes
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    mEnvVars.put(key, value);
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Error reading .env: " + e.getMessage());
        }
    }

    /** Persists the current {@link #mEnvVars} map back to the {@code .env} file. */
    private synchronized void writeEnvFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Hermes Agent Environment Variables\n");
        sb.append("# LLM Provider API Keys\n");

        // Write LLM keys
        writeEnvLine(sb, ENV_OPENAI_API_KEY);
        writeEnvLine(sb, ENV_ANTHROPIC_API_KEY);
        writeEnvLine(sb, ENV_GOOGLE_API_KEY);
        writeEnvLine(sb, ENV_DEEPSEEK_API_KEY);
        writeEnvLine(sb, ENV_OPENROUTER_API_KEY);

        sb.append("\n# Feishu Integration\n");
        writeEnvLine(sb, ENV_FEISHU_APP_ID);
        writeEnvLine(sb, ENV_FEISHU_APP_SECRET);
        writeEnvLine(sb, ENV_FEISHU_DOMAIN);
        writeEnvLine(sb, ENV_FEISHU_CONNECTION_MODE);

        sb.append("\n# Telegram Integration\n");
        writeEnvLine(sb, "TELEGRAM_BOT_TOKEN");
        writeEnvLine(sb, "TELEGRAM_ALLOWED_USERS");

        sb.append("\n# Discord Integration\n");
        writeEnvLine(sb, "DISCORD_BOT_TOKEN");
        writeEnvLine(sb, "DISCORD_ALLOWED_USERS");

        // Write any additional keys that were set dynamically
        String[] knownKeys = {
                ENV_OPENAI_API_KEY, ENV_ANTHROPIC_API_KEY, ENV_GOOGLE_API_KEY,
                ENV_DEEPSEEK_API_KEY, ENV_OPENROUTER_API_KEY,
                ENV_XAI_API_KEY, ENV_ALIBABA_API_KEY, ENV_MISTRAL_API_KEY, ENV_NVIDIA_API_KEY,
                "OLLAMA_API_KEY", "OPENAI_BASE_URL",
                ENV_FEISHU_APP_ID, ENV_FEISHU_APP_SECRET,
                ENV_FEISHU_DOMAIN, ENV_FEISHU_CONNECTION_MODE,
                "TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS",
                "DISCORD_BOT_TOKEN", "DISCORD_ALLOWED_USERS"
        };
        for (Map.Entry<String, String> entry : mEnvVars.entrySet()) {
            boolean isKnown = false;
            for (String known : knownKeys) {
                if (known.equals(entry.getKey())) {
                    isKnown = true;
                    break;
                }
            }
            if (!isKnown) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
        }

        writeStringToFile(ENV_FILE_PATH, sb.toString());
        Logger.logDebug(LOG_TAG, ".env written");
    }

    private void writeEnvLine(StringBuilder sb, String key) {
        String value = mEnvVars.get(key);
        sb.append(key).append("=").append(value != null ? value : "").append("\n");
    }

    // =========================================================================
    // Generic getters / setters for config.yaml values
    // =========================================================================

    /** Returns a value from config.yaml by flattened dotted key, or {@code null}. */
    public String getYamlValue(String flatKey) {
        return mYamlConfig.get(flatKey);
    }

    /** Returns a value from config.yaml by flattened dotted key, or {@code defaultValue}. */
    public String getYamlValue(String flatKey, String defaultValue) {
        String v = mYamlConfig.get(flatKey);
        return (v != null) ? v : defaultValue;
    }

    /** Sets a value in config.yaml by flattened dotted key and persists. */
    public synchronized void setYamlValue(String flatKey, String value) {
        mYamlConfig.put(flatKey, value);
        writeYamlConfig();
    }

    // =========================================================================
    // MCP Server config
    // =========================================================================

    /** Returns all configured MCP servers as a map of name -> config value. */
    public Map<String, String> getMcpServers() {
        Map<String, String> servers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mYamlConfig.entrySet()) {
            if (entry.getKey().startsWith("mcp_servers.") && !entry.getKey().endsWith(".command")
                    && !entry.getKey().endsWith(".url") && !entry.getKey().endsWith(".args")
                    && !entry.getKey().endsWith(".env")) {
                String name = entry.getKey().substring("mcp_servers.".length());
                String value = entry.getValue();
                // Also check for sub-keys
                String cmd = mYamlConfig.get("mcp_servers." + name + ".command");
                String url = mYamlConfig.get("mcp_servers." + name + ".url");
                if (cmd != null && !cmd.isEmpty()) {
                    servers.put(name, cmd);
                } else if (url != null && !url.isEmpty()) {
                    servers.put(name, "url:" + url);
                } else {
                    servers.put(name, value);
                }
            }
        }
        return servers;
    }

    /** Adds an MCP server configuration. */
    public synchronized void addMcpServer(String name, String configValue) {
        if (configValue.startsWith("url:")) {
            setYamlValue("mcp_servers." + name + ".url", configValue.substring(4));
        } else {
            setYamlValue("mcp_servers." + name + ".command", configValue);
        }
    }

    /** Removes an MCP server configuration. */
    public synchronized void removeMcpServer(String name) {
        String prefix = "mcp_servers." + name;
        java.util.List<String> toRemove = new ArrayList<>();
        for (String key : mYamlConfig.keySet()) {
            if (key.equals(prefix) || key.startsWith(prefix + ".")) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            mYamlConfig.remove(key);
        }
        writeYamlConfig();
    }

    // =========================================================================
    // Terminal config
    // =========================================================================

    public String getTerminalBackend() {
        return getYamlValue(KEY_TERMINAL_BACKEND, "local");
    }

    public void setTerminalBackend(String backend) {
        setYamlValue(KEY_TERMINAL_BACKEND, backend);
    }

    public String getTerminalCwd() {
        return getYamlValue(KEY_TERMINAL_CWD, "");
    }

    public void setTerminalCwd(String cwd) {
        setYamlValue(KEY_TERMINAL_CWD, cwd);
    }

    // =========================================================================
    // Model config
    // =========================================================================

    public String getModelProvider() {
        return getYamlValue(KEY_MODEL_PROVIDER, "openai");
    }

    public void setModelProvider(String provider) {
        setYamlValue(KEY_MODEL_PROVIDER, provider);
    }

    public String getModelName() {
        return getYamlValue(KEY_MODEL_NAME, "gpt-4");
    }

    public void setModelName(String modelName) {
        setYamlValue(KEY_MODEL_NAME, modelName);
    }

    public float getModelTemperature() {
        String val = getYamlValue(KEY_MODEL_TEMPERATURE, "0.7");
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return 0.7f;
        }
    }

    public void setModelTemperature(float temperature) {
        setYamlValue(KEY_MODEL_TEMPERATURE, String.valueOf(temperature));
    }

    public int getModelMaxTokens() {
        String val = getYamlValue(KEY_MODEL_MAX_TOKENS, "4096");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 4096;
        }
    }

    public void setModelMaxTokens(int maxTokens) {
        setYamlValue(KEY_MODEL_MAX_TOKENS, String.valueOf(maxTokens));
    }

    public float getModelTopP() {
        String val = getYamlValue(KEY_MODEL_TOP_P, "1.0");
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public void setModelTopP(float topP) {
        setYamlValue(KEY_MODEL_TOP_P, String.valueOf(topP));
    }

    public float getModelFrequencyPenalty() {
        String val = getYamlValue(KEY_MODEL_FREQUENCY_PENALTY, "0.0");
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    public void setModelFrequencyPenalty(float penalty) {
        setYamlValue(KEY_MODEL_FREQUENCY_PENALTY, String.valueOf(penalty));
    }

    public float getModelPresencePenalty() {
        String val = getYamlValue(KEY_MODEL_PRESENCE_PENALTY, "0.0");
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    public void setModelPresencePenalty(float penalty) {
        setYamlValue(KEY_MODEL_PRESENCE_PENALTY, String.valueOf(penalty));
    }

    public int getModelCommandTimeout() {
        String val = getYamlValue(KEY_MODEL_COMMAND_TIMEOUT, "120");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 120;
        }
    }

    public void setModelCommandTimeout(int timeout) {
        setYamlValue(KEY_MODEL_COMMAND_TIMEOUT, String.valueOf(timeout));
    }

    public String getSystemPrompt() {
        return getYamlValue(KEY_SYSTEM_PROMPT, "");
    }

    public void setSystemPrompt(String prompt) {
        setYamlValue(KEY_SYSTEM_PROMPT, prompt != null ? prompt : "");
    }

    // =========================================================================
    // API key access (stored in .env)
    // =========================================================================

    /**
     * Returns the API key for the given provider name (case-insensitive).
     * <p>
     * Recognised provider names:
     * {@code openai}, {@code anthropic}, {@code google}, {@code deepseek}, {@code openrouter}.
     * You may also pass the exact env var name directly (e.g. {@code "OPENAI_API_KEY"}).
     *
     * @param provider the provider short name or full env var name
     * @return the key or an empty string if not set
     */
    public String getApiKey(String provider) {
        if (provider == null) return "";
        String envKey = providerToEnvKey(provider);
        String value = mEnvVars.get(envKey);
        return (value != null) ? value : "";
    }

    /**
     * Sets the API key for the given provider name (case-insensitive) and persists the {@code .env}
     * file.
     *
     * @param provider the provider short name or full env var name
     * @param key      the API key value
     */
    public synchronized void setApiKey(String provider, String key) {
        if (provider == null) return;
        String envKey = providerToEnvKey(provider);
        mEnvVars.put(envKey, key != null ? key : "");
        writeEnvFile();
    }

    /**
     * Maps a provider short name to the corresponding {@code .env} variable name.
     * If the provider string already looks like an env var (contains an underscore and is
     * uppercase), it is returned as-is.
     */
    private static String providerToEnvKey(String provider) {
        String upper = provider.toUpperCase();
        // If it already looks like an env var, return it directly
        if (upper.contains("_")) {
            return upper;
        }
        switch (upper) {
            case "OPENAI":
                return ENV_OPENAI_API_KEY;
            case "ANTHROPIC":
                return ENV_ANTHROPIC_API_KEY;
            case "GOOGLE":
                return ENV_GOOGLE_API_KEY;
            case "DEEPSEEK":
                return ENV_DEEPSEEK_API_KEY;
            case "OPENROUTER":
                return ENV_OPENROUTER_API_KEY;
            case "XAI":
                return ENV_XAI_API_KEY;
            case "ALIBABA":
                return ENV_ALIBABA_API_KEY;
            case "MISTRAL":
                return ENV_MISTRAL_API_KEY;
            case "NVIDIA":
                return ENV_NVIDIA_API_KEY;
            case "OLLAMA":
                return "OLLAMA_API_KEY";
            default:
                // Fallback: provider name + _API_KEY
                return upper + "_API_KEY";
        }
    }

    // =========================================================================
    // Feishu config (stored in config.yaml)
    // =========================================================================

    /**
     * Returns all Feishu-related configuration as a map with the following keys:
     * {@code app_id}, {@code app_secret}, {@code domain}, {@code connection_mode},
     * {@code allowed_users}, {@code home_channel}.
     */
    public Map<String, String> getFeishuConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("app_id", getYamlValue(KEY_FEISHU_APP_ID, ""));
        config.put("app_secret", getYamlValue(KEY_FEISHU_APP_SECRET, ""));
        config.put("domain", getYamlValue(KEY_FEISHU_DOMAIN, "feishu"));
        config.put("connection_mode", getYamlValue(KEY_FEISHU_CONNECTION_MODE, "websocket"));
        config.put("allowed_users", getYamlValue(KEY_FEISHU_ALLOWED_USERS, ""));
        config.put("home_channel", getYamlValue(KEY_FEISHU_HOME_CHANNEL, ""));
        return config;
    }

    /**
     * Updates Feishu configuration and persists config.yaml.
     * {@code null} values are ignored (existing value is kept).
     */
    public synchronized void setFeishuConfig(String appId, String appSecret, String domain,
                                              String connectionMode, String allowedUsers,
                                              String homeChannel) {
        if (appId != null) mYamlConfig.put(KEY_FEISHU_APP_ID, appId);
        if (appSecret != null) mYamlConfig.put(KEY_FEISHU_APP_SECRET, appSecret);
        if (domain != null) mYamlConfig.put(KEY_FEISHU_DOMAIN, domain);
        if (connectionMode != null) mYamlConfig.put(KEY_FEISHU_CONNECTION_MODE, connectionMode);
        if (allowedUsers != null) mYamlConfig.put(KEY_FEISHU_ALLOWED_USERS, allowedUsers);
        if (homeChannel != null) mYamlConfig.put(KEY_FEISHU_HOME_CHANNEL, homeChannel);
        writeYamlConfig();
    }

    // Convenience accessors for individual Feishu fields

    public String getFeishuAppId() {
        return getYamlValue(KEY_FEISHU_APP_ID, "");
    }

    public void setFeishuAppId(String appId) {
        setYamlValue(KEY_FEISHU_APP_ID, appId);
    }

    public String getFeishuAppSecret() {
        return getYamlValue(KEY_FEISHU_APP_SECRET, "");
    }

    public void setFeishuAppSecret(String appSecret) {
        setYamlValue(KEY_FEISHU_APP_SECRET, appSecret);
    }

    public String getFeishuDomain() {
        return getYamlValue(KEY_FEISHU_DOMAIN, "feishu");
    }

    public void setFeishuDomain(String domain) {
        setYamlValue(KEY_FEISHU_DOMAIN, domain);
    }

    public String getFeishuConnectionMode() {
        return getYamlValue(KEY_FEISHU_CONNECTION_MODE, "websocket");
    }

    public void setFeishuConnectionMode(String mode) {
        setYamlValue(KEY_FEISHU_CONNECTION_MODE, mode);
    }

    public String getFeishuAllowedUsers() {
        return getYamlValue(KEY_FEISHU_ALLOWED_USERS, "");
    }

    public void setFeishuAllowedUsers(String users) {
        setYamlValue(KEY_FEISHU_ALLOWED_USERS, users);
    }

    public String getFeishuHomeChannel() {
        return getYamlValue(KEY_FEISHU_HOME_CHANNEL, "");
    }

    public void setFeishuHomeChannel(String channel) {
        setYamlValue(KEY_FEISHU_HOME_CHANNEL, channel);
    }

    // =========================================================================
    // Status helpers
    // =========================================================================

    /**
     * Returns {@code true} if at least one LLM API key is configured and the
     * model provider/name are set.
     */
    public boolean isConfigured() {
        String provider = getModelProvider();
        if (provider.isEmpty()) return false;

        String apiKey = getApiKey(provider);
        if (apiKey.isEmpty()) {
            // Check all known providers
            String[] providers = {"openai", "anthropic", "google", "deepseek", "openrouter",
                    "xai", "alibaba", "mistral", "nvidia", "ollama", "custom"};
            boolean anyKey = false;
            for (String p : providers) {
                if (!getApiKey(p).isEmpty()) {
                    anyKey = true;
                    break;
                }
            }
            if (!anyKey) return false;
        }

        return !getModelName().isEmpty();
    }

    /**
     * Returns {@code true} if Feishu integration has the minimum required
     * credentials configured (app_id and app_secret non-empty).
     */
    public boolean isFeishuConfigured() {
        return !getFeishuAppId().isEmpty() && !getFeishuAppSecret().isEmpty();
    }

    // =========================================================================
    // Raw env var access
    // =========================================================================

    /** Returns a raw environment variable value from the {@code .env} file. */
    public String getEnvVar(String key) {
        String value = mEnvVars.get(key);
        return (value != null) ? value : "";
    }

    /** Sets a raw environment variable value and persists the {@code .env} file. */
    public synchronized void setEnvVar(String key, String value) {
        mEnvVars.put(key, value != null ? value : "");
        writeEnvFile();
    }

    /** Returns a copy of all parsed environment variables. */
    public Map<String, String> getAllEnvVars() {
        return new HashMap<>(mEnvVars);
    }

    /** Returns a copy of all parsed YAML config values. */
    public Map<String, String> getAllYamlConfig() {
        return new HashMap<>(mYamlConfig);
    }

    // =========================================================================
    // Reset
    // =========================================================================

    /** Deletes config files and reloads defaults. */
    public synchronized void resetToDefaults() {
        new File(CONFIG_YAML_PATH).delete();
        new File(ENV_FILE_PATH).delete();
        mYamlConfig.clear();
        mEnvVars.clear();
        writeDefaultConfigYaml();
        writeDefaultEnvFile();
        loadConfig();
        Logger.logInfo(LOG_TAG, "Configuration reset to defaults");
    }

    // =========================================================================
    // File utilities
    // =========================================================================

    private static void writeStringToFile(String path, String content) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                Logger.logError(LOG_TAG, "Failed to create parent directory for: " + path);
                return;
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Error writing to " + path + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Gateway session tracking
    // =========================================================================

    private static final String SESSION_PREFS_NAME = "hermes_gateway_sessions";
    private static final String KEY_SESSION_COUNT = "session_count";
    private static final int MAX_SESSIONS = 10;

    /** Simple data class representing a completed gateway session. */
    public static class Session {
        public final long startTime;
        public final long stopTime;
        public final long duration;

        public Session(long startTime, long stopTime, long duration) {
            this.startTime = startTime;
            this.stopTime = stopTime;
            this.duration = duration;
        }
    }

    /**
     * Records the start of a gateway session.
     * Stores the current timestamp so that {@link #recordSessionStop(Context)} can compute duration.
     */
    public void recordSessionStart(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong("pending_start_time", System.currentTimeMillis()).apply();
    }

    /**
     * Records the end of the current gateway session.
     * Reads the pending start time, computes duration, and appends the session to history.
     * Oldest sessions are removed when the list exceeds {@link #MAX_SESSIONS}.
     */
    public void recordSessionStop(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE);
        long startTime = prefs.getLong("pending_start_time", 0);
        if (startTime == 0) return;

        long stopTime = System.currentTimeMillis();
        long duration = stopTime - startTime;

        int count = prefs.getInt(KEY_SESSION_COUNT, 0);

        // Shift sessions if at max capacity
        int startIdx = 0;
        if (count >= MAX_SESSIONS) {
            startIdx = 1;
        } else {
            count++;
        }

        SharedPreferences.Editor editor = prefs.edit();

        // Shift existing sessions (drop oldest)
        for (int i = startIdx; i < MAX_SESSIONS - 1; i++) {
            long st = prefs.getLong("session_" + i + "_start", 0);
            long sp = prefs.getLong("session_" + i + "_stop", 0);
            long du = prefs.getLong("session_" + i + "_duration", 0);
            if (st == 0) break;
            int targetIdx = i - startIdx;
            editor.putLong("session_" + targetIdx + "_start", st);
            editor.putLong("session_" + targetIdx + "_stop", sp);
            editor.putLong("session_" + targetIdx + "_duration", du);
        }

        // Write new session at the end
        int newIdx = count - 1;
        editor.putLong("session_" + newIdx + "_start", startTime);
        editor.putLong("session_" + newIdx + "_stop", stopTime);
        editor.putLong("session_" + newIdx + "_duration", duration);
        editor.putInt(KEY_SESSION_COUNT, count);
        editor.remove("pending_start_time");
        editor.apply();
    }

    /**
     * Returns the list of recorded gateway sessions, newest last.
     * Returns an empty list if no sessions have been recorded.
     */
    public List<Session> getSessionHistory(Context context) {
        List<Session> sessions = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_SESSION_COUNT, 0);
        for (int i = 0; i < count; i++) {
            long start = prefs.getLong("session_" + i + "_start", 0);
            long stop = prefs.getLong("session_" + i + "_stop", 0);
            long dur = prefs.getLong("session_" + i + "_duration", 0);
            if (start > 0) {
                sessions.add(new Session(start, stop, dur));
            }
        }
        return sessions;
    }

    /** Deletes all stored session history. */
    public void clearSessionHistory(Context context) {
        context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Returns total uptime across all sessions in milliseconds. */
    public long getTotalUptimeMs(Context context) {
        long total = 0;
        for (Session s : getSessionHistory(context)) {
            total += s.duration;
        }
        // Add current session if running
        if (HermesGatewayService.isRunning()) {
            String uptimeStr = HermesGatewayService.getFormattedUptime();
            if (!uptimeStr.isEmpty()) {
                // Parse current uptime from the formatted string (approximate)
                total += System.currentTimeMillis() - getPendingStartTime(context);
            }
        }
        return total;
    }

    private long getPendingStartTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong("pending_start_time", 0);
    }

    /** Returns number of completed sessions. */
    public int getSessionCount(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_SESSION_COUNT, 0);
    }

    public static void restartGatewayIfRunning(Context context) {
        HermesGatewayStatus.checkAsync((status, detail) -> {
            if (status == HermesGatewayStatus.Status.RUNNING) {
                Intent intent = new Intent(context, HermesGatewayService.class);
                intent.setAction(HermesGatewayService.ACTION_RESTART);
                context.startService(intent);
            }
        });
    }

    // =========================================================================
    // Profile management
    // =========================================================================

    private static final String PROFILES_PREFS = "hermes_profiles";
    private static final String KEY_PROFILES_JSON = "profiles_json";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";

    /** Returns list of saved profile names. */
    public List<String> getProfileNames(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PROFILES_JSON, "{}");
        List<String> names = new ArrayList<>();
        try {
            String[] parts = json.replace("{", "").replace("}", "").replace("\"", "").split(",");
            for (String part : parts) {
                String[] kv = part.split(":");
                if (kv.length >= 1 && !kv[0].trim().isEmpty()) {
                    names.add(kv[0].trim());
                }
            }
        } catch (Exception ignored) {}
        return names;
    }

    /** Returns the currently active profile name, or "Default" if none selected. */
    public String getActiveProfileName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ACTIVE_PROFILE, "Default");
    }

    /** Sets the active profile by name. */
    public void setActiveProfile(Context context, String profileName) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileName).apply();
    }

    /** Saves the current configuration as a named profile. */
    public void saveProfile(Context context, String profileName) {
        if (profileName == null || profileName.isEmpty()) return;
        String provider = getModelProvider();
        String apiKey = getApiKey(provider);
        String model = getModelName();
        float temp = getModelTemperature();
        int maxTokens = getModelMaxTokens();
        String systemPrompt = getSystemPrompt();
        String baseUrl = getEnvVar("OPENAI_BASE_URL");

        String profileData = provider + "|" + (apiKey != null ? apiKey : "") + "|" + model
                + "|" + temp + "|" + maxTokens + "|" + systemPrompt.replace("|", "\\|") + "|" + baseUrl;

        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PROFILES_JSON, "{}");
        String entry = "\"" + profileName + "\":\"" + profileData.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (json.equals("{}")) {
            json = "{" + entry + "}";
        } else {
            json = json.replaceAll("\"" + profileName + "\":\"[^\"]*\",?", "").trim();
            if (json.endsWith(",")) json = json.substring(0, json.length() - 1);
            if (json.equals("{")) json = "{" + entry + "}";
            else json = json.substring(0, json.length() - 1) + "," + entry + "}";
        }
        prefs.edit().putString(KEY_PROFILES_JSON, json).apply();
        setActiveProfile(context, profileName);
    }

    /** Loads a saved profile by name. Returns true if profile was found and loaded. */
    public boolean loadProfile(Context context, String profileName) {
        if (profileName == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PROFILES_JSON, "{}");

        String key = "\"" + profileName + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return false;
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return false;
        String profileData = json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");

        String[] parts = profileData.split("\\|", -1);
        if (parts.length < 5) return false;

        setModelProvider(parts[0]);
        if (!parts[1].isEmpty()) setApiKey(parts[0], parts[1]);
        setModelName(parts[2]);
        try { setModelTemperature(Float.parseFloat(parts[3])); } catch (Exception ignored) {}
        try { setModelMaxTokens(Integer.parseInt(parts[4])); } catch (Exception ignored) {}
        if (parts.length >= 6) setSystemPrompt(parts[5].replace("\\|", "|"));
        if (parts.length >= 7 && !parts[6].isEmpty()) setEnvVar("OPENAI_BASE_URL", parts[6]);

        setActiveProfile(context, profileName);
        return true;
    }

    /** Deletes a saved profile by name. */
    public void deleteProfile(Context context, String profileName) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILES_PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PROFILES_JSON, "{}");
        json = json.replaceAll("\"" + profileName + "\":\"[^\"]*\",?", "");
        json = json.replace(",}", "}").replace("{,", "{");
        if (json.trim().equals("") || json.trim().equals("{}")) json = "{}";
        prefs.edit().putString(KEY_PROFILES_JSON, json).apply();
        if (getActiveProfileName(context).equals(profileName)) {
            setActiveProfile(context, "Default");
        }
    }

    /** Exports all config to a JSON string suitable for backup. */
    public String exportConfig() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // YAML config
        sb.append("  \"yaml\": {\n");
        boolean first = true;
        for (Map.Entry<String, String> entry : mYamlConfig.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(entry.getKey()).append("\": \"");
            sb.append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("\n  },\n");

        // Env vars
        sb.append("  \"env\": {\n");
        first = true;
        for (Map.Entry<String, String> entry : mEnvVars.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(entry.getKey()).append("\": \"");
            sb.append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("\n  }\n");

        sb.append("}");
        return sb.toString();
    }

    /** Exports config with sensitive values masked. */
    public String exportConfigMasked() {
        ensureLoaded();
        String[] sensitiveKeys = {
                "API_KEY", "SECRET", "TOKEN", "PASSWORD",
                "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY",
                "DEEPSEEK_API_KEY", "OPENROUTER_API_KEY", "XAI_API_KEY",
                "DASHSCOPE_API_KEY", "MISTRAL_API_KEY", "NVIDIA_API_KEY",
                "FEISHU_APP_SECRET", "TELEGRAM_BOT_TOKEN", "DISCORD_BOT_TOKEN",
                "WHATSAPP_ACCESS_TOKEN"
        };

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        sb.append("  \"yaml\": {\n");
        boolean first = true;
        for (Map.Entry<String, String> entry : mYamlConfig.entrySet()) {
            if (!first) sb.append(",\n");
            String value = entry.getValue();
            for (String key : sensitiveKeys) {
                if (entry.getKey().toUpperCase().contains(key)) {
                    value = maskSensitive(value);
                    break;
                }
            }
            sb.append("    \"").append(entry.getKey()).append("\": \"");
            sb.append(escapeJson(value)).append("\"");
            first = false;
        }
        sb.append("\n  },\n");

        sb.append("  \"env\": {\n");
        first = true;
        for (Map.Entry<String, String> entry : mEnvVars.entrySet()) {
            if (!first) sb.append(",\n");
            String value = entry.getValue();
            for (String key : sensitiveKeys) {
                if (entry.getKey().toUpperCase().contains(key)) {
                    value = maskSensitive(value);
                    break;
                }
            }
            sb.append("    \"").append(entry.getKey()).append("\": \"");
            sb.append(escapeJson(value)).append("\"");
            first = false;
        }
        sb.append("\n  }\n");

        sb.append("}");
        return sb.toString();
    }

    /** Imports config from a JSON string, overwriting existing config. */
    public boolean importConfig(String json) {
        try {
            // Parse yaml section
            String yamlSection = extractSection(json, "yaml");
            String envSection = extractSection(json, "env");

            if (yamlSection != null) {
                Map<String, String> yamlMap = parseJsonEntries(yamlSection);
                for (Map.Entry<String, String> entry : yamlMap.entrySet()) {
                    mYamlConfig.put(entry.getKey(), entry.getValue());
                }
                writeYamlConfig();
            }

            if (envSection != null) {
                Map<String, String> envMap = parseJsonEntries(envSection);
                for (Map.Entry<String, String> entry : envMap.entrySet()) {
                    mEnvVars.put(entry.getKey(), entry.getValue());
                }
                writeEnvFile();
            }

            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to import config: " + e.getMessage());
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String maskSensitive(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String extractSection(String json, String sectionName) {
        String marker = "\"" + sectionName + "\": {";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int depth = 1;
        int end = start;
        while (end < json.length() && depth > 0) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            end++;
        }
        return json.substring(start, end - 1);
    }

    private Map<String, String> parseJsonEntries(String section) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = section.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("\"")) {
                int colonIdx = line.indexOf("\":");
                if (colonIdx > 0) {
                    String key = line.substring(1, colonIdx);
                    String value = line.substring(colonIdx + 2).trim();
                    // Remove surrounding quotes and trailing comma
                    if (value.startsWith("\"")) value = value.substring(1);
                    if (value.endsWith("\"")) value = value.substring(0, value.length() - 1);
                    else if (value.endsWith("\",")) value = value.substring(0, value.length() - 2);
                    // Unescape
                    value = value.replace("\\n", "\n").replace("\\r", "\r")
                            .replace("\\t", "\t").replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                    map.put(key, value);
                }
            }
        }
        return map;
    }