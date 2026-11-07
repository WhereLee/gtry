package com.wherelee.helpdesk.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();

    @Test
    void streamsAnAnswerBuiltFromRetrievedSnippets() {
        List<String> tokens = new ArrayList<>();

        int usage = client.stream(
                new LlmRequest("mock", "system", "question", List.of("7 天无理由退货，拆封商品不支持。")),
                tokens::add
        );

        assertThat(String.join("", tokens)).contains("7 天无理由退货");
        assertThat(usage).isGreaterThan(0);
    }

    @Test
    void saysSoWhenNothingWasRetrieved() {
        List<String> tokens = new ArrayList<>();

        client.stream(new LlmRequest("mock", "system", "question", List.of()), tokens::add);

        assertThat(String.join("", tokens)).contains("知识库里没有找到");
    }
}
