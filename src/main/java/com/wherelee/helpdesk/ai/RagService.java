package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.wiki.WikiHit;
import com.wherelee.helpdesk.wiki.WikiSearchResult;
import com.wherelee.helpdesk.wiki.WikiSearchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;

@Service
public class RagService {

    private static final String SYSTEM_PROMPT = """
            你是一个企业客服助手。只能依据提供的知识库片段回答，不要编造事实。
            回答使用中文，简洁直接；如果片段不足以回答，明确说明并建议转人工。
            引用规则时优先说明适用条件、时效和例外情况。
            """;

    private final WikiSearchService searchService;
    private final LlmClient llmClient;
    private final HelpdeskProperties.Rag config;
    private final HelpdeskProperties.Wiki wikiConfig;
    private final Timer llmTimer;

    public RagService(
            WikiSearchService searchService,
            LlmClient llmClient,
            HelpdeskProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.searchService = searchService;
        this.llmClient = llmClient;
        this.config = properties.getRag();
        this.wikiConfig = properties.getWiki();
        this.llmTimer = Timer.builder("helpdesk_llm_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
    }

    public List<WikiHit> retrieve(String question) {
        return searchService.search(
                question, config.getTopK(), wikiConfig.isIncludeInternalDefault()).hits();
    }

    public RagResult answer(String question, Consumer<String> onToken) {
        WikiSearchResult search = searchService.search(
                question, config.getTopK(), wikiConfig.isIncludeInternalDefault());
        List<WikiHit> hits = search.hits();
        List<String> snippets = hits.stream()
                .map(hit -> "《" + hit.pageTitle() + "》 " + hit.headingPath() + "：" + hit.content())
                .toList();

        StringBuilder userPrompt = new StringBuilder("【知识库片段】\n");
        for (int i = 0; i < snippets.size(); i++) {
            userPrompt.append('[').append(i + 1).append("] ").append(snippets.get(i)).append('\n');
        }
        if (snippets.isEmpty()) {
            userPrompt.append("（没有检索到相关片段）\n");
        }
        userPrompt.append("\n【客户问题】\n").append(question);

        try {
            long started = System.nanoTime();
            int tokens = llmClient.stream(
                    new LlmRequest(SYSTEM_PROMPT, userPrompt.toString(), snippets),
                    onToken
            );
            llmTimer.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            return new RagResult(tokens, hits, search.traceId());
        } catch (UncheckedIOException | ClientDisconnectedException ex) {
            // The client disconnected mid-stream. The caller still persists the
            // partial answer; tokens=0 tells it not to send the done event.
            return new RagResult(0, hits, search.traceId());
        }
    }

    public record RagResult(int tokens, List<WikiHit> hits, String traceId) {
    }
}
