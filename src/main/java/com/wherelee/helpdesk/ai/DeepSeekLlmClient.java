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
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepSeek is OpenAI-compatible: POST /chat/completions with stream=true and parse
 * the SSE `data:` lines. Disabled by default; enable with
 * HELPDESK_LLM_PROVIDER=deepseek and HELPDESK_LLM_API_KEY.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.llm.provider", havingValue = "deepseek")
public class DeepSeekLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);

    private final HelpdeskProperties.Llm config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekLlmClient(HelpdeskProperties properties, ObjectMapper objectMapper) {
        this.config = properties.getLlm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public int stream(LlmRequest request, Consumer<String> onToken) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("HELPDESK_LLM_API_KEY is not configured");
        }
        int maxAttempts = Math.max(1, config.getMaxAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            AtomicInteger emitted = new AtomicInteger();
            Consumer<String> tracked = token -> {
                emitted.incrementAndGet();
                onToken.accept(token);
            };
            try {
                return streamOnce(request, tracked);
            } catch (UncheckedIOException | ClientDisconnectedException ex) {
                // client disconnected while streaming; let the caller persist partial text
                throw ex;
            } catch (Exception ex) {
                lastFailure = new IllegalStateException("deepseek request failed", ex);
                // Retrying after tokens were emitted would duplicate the answer.
                if (emitted.get() > 0 || attempt == maxAttempts) {
                    throw lastFailure;
                }
                log.warn("helpdesk_llm_retry attempt={} error={}", attempt, ex.getMessage());
                sleep(300L * attempt);
            }
        }
        throw lastFailure == null ? new IllegalStateException("deepseek request failed") : lastFailure;
    }

    private int streamOnce(LlmRequest request, Consumer<String> onToken) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", config.getModel());
        payload.put("stream", true);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        ));
        if (config.getEffort() != null && !config.getEffort().isBlank()) {
            payload.put("reasoning_effort", config.getEffort());
        }
        payload.put("stream_options", Map.of("include_usage", true));
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder(
                        URI.create(trimSlash(config.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("deepseek failed: HTTP " + response.statusCode());
        }
        int[] usage = {0};
        StringBuilder collected = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> consume(line, onToken, collected, usage));
        }
        return usage[0] > 0 ? usage[0] : Math.max(1, collected.length() / 2);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void consume(String line, Consumer<String> onToken, StringBuilder collected, int[] usage) {
        if (!line.startsWith("data:")) {
            return;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode usageNode = node.path("usage").path("total_tokens");
            if (!usageNode.isMissingNode()) {
                usage[0] = usageNode.asInt();
            }
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                String token = content.asText();
                if (!token.isEmpty()) {
                    collected.append(token);
                    onToken.accept(token);
                }
            }
        } catch (Exception ignored) {
            // ignore malformed keep-alive lines
        }
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
