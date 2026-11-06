package com.wherelee.helpdesk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Works with any OpenAI-compatible /embeddings endpoint (Ollama, vLLM, etc.).
 * DeepSeek currently exposes chat models, so embeddings need a separate provider.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.embedding.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final HelpdeskProperties.Embedding config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingClient(HelpdeskProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getEmbedding();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public int dimension() {
        return config.getDimension();
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", config.getModel(),
                    "input", text == null ? "" : text
            ));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(trimSlash(config.getBaseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("embedding failed: HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode values = root.path("data").path(0).path("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            if (vector.length != config.getDimension()) {
                throw new IllegalStateException("embedding dimension mismatch: expected "
                        + config.getDimension() + " got " + vector.length);
            }
            return vector;
        } catch (Exception ex) {
            throw new IllegalStateException("embedding request failed", ex);
        }
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
