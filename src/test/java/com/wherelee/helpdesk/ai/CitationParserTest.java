package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.wiki.WikiHit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CitationParserTest {

    private final List<WikiHit> hits = List.of(hit("A"), hit("B"), hit("C"));

    @Test
    void mapsMarkersToSnippets() {
        assertThat(CitationParser.parse("按 [2] 和 [1] 处理", hits))
                .extracting(WikiHit::pageTitle)
                .containsExactly("B", "A");
    }

    @Test
    void ignoresOutOfRangeMarkersAndDuplicates() {
        assertThat(CitationParser.parse("[9] [1] [1]", hits))
                .extracting(WikiHit::pageTitle)
                .containsExactly("A");
    }

    @Test
    void returnsEmptyWhenModelDidNotCite() {
        assertThat(CitationParser.parse("没有引用", hits)).isEmpty();
    }

    private WikiHit hit(String title) {
        return new WikiHit(UUID.randomUUID(), UUID.randomUUID(), title, title + " > 适用条件",
                "content", "PUBLIC", Instant.now(), 0.9, 0.1, 0.9);
    }
}
