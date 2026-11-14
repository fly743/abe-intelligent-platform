package com.abe.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "abe.rag")
public class RagProperties {

    private boolean enabled = true;
    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();
    private final Retrieve retrieve = new Retrieve();
    private final Ingest ingest = new Ingest();
    private final DevEndpoints devEndpoints = new DevEndpoints();
    private final Chat chat = new Chat();


    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Embedding getEmbedding() { return embedding; }
    public Qdrant getQdrant() { return qdrant; }
    public Retrieve getRetrieve() { return retrieve; }
    public Ingest getIngest() { return ingest; }
    public DevEndpoints getDevEndpoints() { return devEndpoints; }
    public Chat getChat() { return chat; }


    public static class DevEndpoints {
        private boolean ingestEnabled = false;
        private boolean allowRecreate = false;

        public boolean isIngestEnabled() { return ingestEnabled; }
        public void setIngestEnabled(boolean v) { this.ingestEnabled = v; }
        public boolean isAllowRecreate() { return allowRecreate; }
        public void setAllowRecreate(boolean v) { this.allowRecreate = v; }
    }

    public static class Embedding {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        private String apiKey = "";
        private String model = "text-embedding-v4";
        private int dimensions = 1024;
        /** 单次请求最多几条文本：百炼限 10。 */
        private int batchSize = 10;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int v) { this.dimensions = v; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { this.batchSize = v; }
    }

    public static class Qdrant {
        private String host = "localhost";
        private int port = 6334;
        private boolean useTls = false;
        private String apiKey = "";
        private String collection = "abe_knowledge";
        private boolean initializeSchema = true;

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
        public boolean isInitializeSchema() { return initializeSchema; }
        public void setInitializeSchema(boolean v) { this.initializeSchema = v; }
    }

    public static class Retrieve {
        private int topK = 8;
        /** 对询问密文和密钥属性关系的问题补充双向术语，便于召回比较资料。 */
        private boolean relationshipQueryExpansion = true;
        /** 当前供应商相关样本约 0.2，先取 0.15；有正式语料后按正负样本分布精调。 */
        private double scoreThreshold = 0.15D;

        public int getTopK() { return topK; }
        public void setTopK(int v) { this.topK = v; }
        public double getScoreThreshold() { return scoreThreshold; }
        public void setScoreThreshold(double v) { this.scoreThreshold = v; }
        public boolean isRelationshipQueryExpansion() { return relationshipQueryExpansion; }
        public void setRelationshipQueryExpansion(boolean v) { this.relationshipQueryExpansion = v; }
    }

    public static class Ingest {
        /** 相对模块工作目录 D:\ABE-Program\module-a-rag。 */
        private String corpusDir = "docs";
        private int chunkMaxChars = 800;
        private int chunkOverlapChars = 120;
        /** QA 与知识笔记高度重合，默认只摄取知识笔记；按需单独评估 QA。 */
        private boolean includeQa = false;

        public String getCorpusDir() { return corpusDir; }
        public void setCorpusDir(String v) { this.corpusDir = v; }
        public int getChunkMaxChars() { return chunkMaxChars; }
        public void setChunkMaxChars(int v) { this.chunkMaxChars = v; }
        public int getChunkOverlapChars() { return chunkOverlapChars; }
        public void setChunkOverlapChars(int v) { this.chunkOverlapChars = v; }
        public boolean isIncludeQa() { return includeQa; }
        public void setIncludeQa(boolean v) { this.includeQa = v; }
    }

    /** 对话模型：与 embedding 是两个不同端点，必须分开配置。 */
    public static class Chat {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private double temperature = 0.2D;
        private int maxTokens = 1024;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { this.temperature = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
    }

}
