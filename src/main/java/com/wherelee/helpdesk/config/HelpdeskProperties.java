package com.wherelee.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helpdesk")
public class HelpdeskProperties {

    private final Jwt jwt = new Jwt();
    private final Llm llm = new Llm();
    private final Embedding embedding = new Embedding();
    private final Rag rag = new Rag();
    private final Quota quota = new Quota();
    private final Rerank rerank = new Rerank();
    private final Wiki wiki = new Wiki();

    public Jwt getJwt() {
        return jwt;
    }

    public Llm getLlm() {
        return llm;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Rag getRag() {
        return rag;
    }

    public Quota getQuota() {
        return quota;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Wiki getWiki() {
        return wiki;
    }

    public static class Jwt {
        private String secret = "helpdesk-demo-jwt-secret-change-me-32-bytes";
        private long ttlMinutes = 480;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    public static class Llm {
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-flash";
        private String effort = "low";
        private int timeoutSeconds = 60;
        private int maxAttempts = 2;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getEffort() {
            return effort;
        }

        public void setEffort(String effort) {
            this.effort = effort;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    public static class Embedding {
        private String provider = "onnx";
        private int dimension = 768;
        private String modelPath = "D:/models/onnx-int8/bge-base-zh-v1.5-onnx-int8";
        private int maxLength = 512;
        private String queryPrefix = "";
        private String baseUrl = "http://127.0.0.1:11434/v1";
        private String model = "bge-m3";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public String getQueryPrefix() {
            return queryPrefix;
        }

        public void setQueryPrefix(String queryPrefix) {
            this.queryPrefix = queryPrefix;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Rag {
        private int chunkSize = 700;
        private int chunkOverlap = 80;
        private int topK = 5;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }

    public static class Quota {
        private long defaultMonthlyTokens = 200_000;
        private int rateLimitPerMinute = 60;

        public long getDefaultMonthlyTokens() {
            return defaultMonthlyTokens;
        }

        public void setDefaultMonthlyTokens(long defaultMonthlyTokens) {
            this.defaultMonthlyTokens = defaultMonthlyTokens;
        }

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }
    }

    public static class Rerank {
        private boolean enabled = true;
        private String modelPath = "D:/models/onnx-int8/bge-reranker-base-onnx-int8";
        private int maxLength = 512;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }

    public static class Wiki {
        private int vectorCandidates = 20;
        private int ftsCandidates = 20;
        private int rerankCandidates = 10;
        private int rrfK = 60;
        private double freshnessBoost = 0.02;
        private boolean includeInternalDefault = true;

        public int getVectorCandidates() {
            return vectorCandidates;
        }

        public void setVectorCandidates(int vectorCandidates) {
            this.vectorCandidates = vectorCandidates;
        }

        public int getFtsCandidates() {
            return ftsCandidates;
        }

        public void setFtsCandidates(int ftsCandidates) {
            this.ftsCandidates = ftsCandidates;
        }

        public int getRerankCandidates() {
            return rerankCandidates;
        }

        public void setRerankCandidates(int rerankCandidates) {
            this.rerankCandidates = rerankCandidates;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getFreshnessBoost() {
            return freshnessBoost;
        }

        public void setFreshnessBoost(double freshnessBoost) {
            this.freshnessBoost = freshnessBoost;
        }

        public boolean isIncludeInternalDefault() {
            return includeInternalDefault;
        }

        public void setIncludeInternalDefault(boolean includeInternalDefault) {
            this.includeInternalDefault = includeInternalDefault;
        }
    }
}
