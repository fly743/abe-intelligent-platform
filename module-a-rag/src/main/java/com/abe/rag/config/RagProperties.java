package com.abe.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "abe.rag")
public class RagProperties {

    private boolean enabled = true;
    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Embedding getEmbedding() { return embedding; }
    public Qdrant getQdrant() { return qdrant; }

    /** Embedding 供应商（任意 OpenAI 兼容 /v1/embeddings 端点）。 */
    public static class Embedding {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey = "";
        private String model = "text-embedding-v4";
        private int dimensions = 1024;   // <=0 表示不向供应商传 dimensions 参数

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int v) { this.dimensions = v; }
    }

    /** Qdrant 连接参数（gRPC）。 */
    public static class Qdrant {
        private String host = "localhost";
        private int port = 6334;          // gRPC，不是 6333
        private boolean useTls = false;
        private String apiKey = "";
        private String collection = "abe_knowledge";

        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
        public boolean isUseTls() { return useTls; }
        public void setUseTls(boolean v) { this.useTls = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getCollection() { return collection; }
        public void setCollection(String v) { this.collection = v; }
    }
}
