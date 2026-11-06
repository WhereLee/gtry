package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.wiki.WikiHit;
import com.wherelee.helpdesk.wiki.WikiSearchResult;
import com.wherelee.helpdesk.wiki.WikiSearchService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagServiceTest {

    private final WikiSearchService searchService = mock(WikiSearchService.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void returnsOnlySnippetsTheModelActuallyCited() {
        List<WikiHit> hits = List.of(hit("A"), hit("B"));
        when(searchService.search(anyString(), anyInt(), anyBoolean())).thenReturn(result(hits));
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> sink = invocation.getArgument(1);
            sink.accept("按 [2] 处理");
            return 4;
        });

        RagService.RagResult result = service().answer("问题", token -> { });

        assertThat(result.allHits()).hasSize(2);
        assertThat(result.citedHits()).extracting(WikiHit::pageTitle).containsExactly("B");
    }

    @Test
    void fallsBackWhenTheModelFails() {
        when(searchService.search(anyString(), anyInt(), anyBoolean())).thenReturn(result(List.of(hit("A"))));
        when(llmClient.stream(any(), any())).thenThrow(new IllegalStateException("upstream down"));
        List<String> tokens = new ArrayList<>();

        RagService.RagResult result = service().answer("问题", tokens::add);

        assertThat(String.join("", tokens)).contains("AI 服务暂时不可用");
        assertThat(result.citedHits()).isEmpty();
        assertThat(meterRegistry.counter("helpdesk_llm_fallback_total").count()).isEqualTo(1.0);
    }

    private RagService service() {
        return new RagService(searchService, llmClient, new HelpdeskProperties(), meterRegistry);
    }

    private WikiSearchResult result(List<WikiHit> hits) {
        return new WikiSearchResult("trace", hits,
                new WikiSearchResult.Stats(0, 0, 0, 0, 0, 0, 0, 1));
    }

    private WikiHit hit(String title) {
        return new WikiHit(UUID.randomUUID(), UUID.randomUUID(), title, title + " > 规则",
                "content", "PUBLIC", Instant.now(), 0.9, 0.1, 0.9);
    }
}
