package io.github.massimilianopili.mcp.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proprieta' di configurazione per mcp-vector-tools.
 * Supporta 3 provider embedding: ollama, onnx, openai.
 * I valori reali vengono da variabili d'ambiente o application.properties.
 */
@ConfigurationProperties(prefix = "mcp.vector")
public class VectorProperties {

    private boolean enabled;

    /** Provider embedding: ollama | onnx | openai */
    private String provider = "ollama";

    /** Dimensioni vettore (dipende dal modello scelto) */
    private int dimensions = 768;

    // --- Database (pgvector) ---
    private String dbUrl = "jdbc:postgresql://localhost:5432/embeddings";
    private String dbUsername = "postgres";
    private String dbCredential;

    // --- Path ingestion ---
    private String conversationsPath;
    private String docsPath;

    // --- Provider: Ollama ---
    private String ollamaBaseUrl = "http://ollama:11434";
    private String ollamaModel = "nomic-embed-text";

    // --- Provider: ONNX ---
    private String onnxModelCacheDir = "/tmp/onnx-models";

    // --- Provider: OpenAI-compatible ---
    private String openaiBaseUrl = "https://api.openai.com";
    private String openaiApiKey;
    private String openaiModel = "text-embedding-3-small";

    // --- Getters/Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }

    public String getDbUrl() { return dbUrl; }
    public void setDbUrl(String dbUrl) { this.dbUrl = dbUrl; }

    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }

    public String getDbCredential() { return dbCredential; }
    public void setDbCredential(String dbCredential) { this.dbCredential = dbCredential; }

    public String getConversationsPath() { return conversationsPath; }
    public void setConversationsPath(String conversationsPath) { this.conversationsPath = conversationsPath; }

    public String getDocsPath() { return docsPath; }
    public void setDocsPath(String docsPath) { this.docsPath = docsPath; }

    public String getOllamaBaseUrl() { return ollamaBaseUrl; }
    public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }

    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = ollamaModel; }

    public String getOnnxModelCacheDir() { return onnxModelCacheDir; }
    public void setOnnxModelCacheDir(String onnxModelCacheDir) { this.onnxModelCacheDir = onnxModelCacheDir; }

    public String getOpenaiBaseUrl() { return openaiBaseUrl; }
    public void setOpenaiBaseUrl(String openaiBaseUrl) { this.openaiBaseUrl = openaiBaseUrl; }

    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }

    public String getOpenaiModel() { return openaiModel; }
    public void setOpenaiModel(String openaiModel) { this.openaiModel = openaiModel; }
}
