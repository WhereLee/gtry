package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.ai.EmbeddingClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class WikiIndexService {

    private static final Logger log = LoggerFactory.getLogger(WikiIndexService.class);

    private final WikiRepository repository;
    private final EmbeddingClient embeddingClient;
    private final Timer indexTimer;

    public WikiIndexService(WikiRepository repository, EmbeddingClient embeddingClient, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.indexTimer = Timer.builder("helpdesk_index_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
    }

    public void indexSection(UUID sectionId) {
        long started = System.nanoTime();
        WikiRepository.SectionWithPage section = repository.findSection(sectionId)
                .orElseThrow(() -> new IllegalStateException("section not found: " + sectionId));
        String embedText = section.headingPath() + "\n" + section.content();
        String embedding = embeddingClient.embedAsVector(embedText);
        String searchText = ChineseText.searchText(section.headingPath() + " " + section.content());
        repository.upsertChunk(
                section.tenantId(), section.id(), section.pageId(),
                section.content(), searchText, embedding
        );
        indexTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    public int indexPage(UUID pageId) {
        List<UUID> sectionIds = repository.listSectionIds(pageId);
        for (UUID sectionId : sectionIds) {
            indexSection(sectionId);
        }
        return sectionIds.size();
    }

    public int indexSections(List<UUID> sectionIds) {
        int indexed = 0;
        for (UUID sectionId : sectionIds) {
            try {
                indexSection(sectionId);
                indexed++;
            } catch (Exception ex) {
                log.warn("helpdesk_index_section_failed sectionId={} error={}", sectionId, ex.getMessage());
            }
        }
        return indexed;
    }
}
