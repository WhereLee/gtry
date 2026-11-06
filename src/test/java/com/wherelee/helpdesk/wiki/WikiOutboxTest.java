package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "helpdesk.llm.provider=mock",
        "helpdesk.embedding.provider=mock",
        "helpdesk.rerank.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "HELPDESK_IT", matches = "true")
class WikiOutboxTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private WikiRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creatingPageEnqueuesIndexEvent() {
        TenantContext.runWith(TenantScope.platform(ACME), () -> {
            WikiSpace space = repository.createSpace(
                    ACME, "outbox-" + UUID.randomUUID().toString().substring(0, 8),
                    "Outbox Test", "test");
            UUID pageId = null;
            try {
                WikiDtos.PageDetail detail = pageService.create(new WikiPageDtos.CreatePageRequest(
                        space.id(), "Outbox Page", "PUBLIC",
                        List.of(new WikiPageDtos.SectionInput("规则", "内容"))));
                pageId = detail.page().id();
                Integer count = jdbc.queryForObject("""
                        SELECT count(*) FROM outbox_event
                        WHERE aggregate_id = ? AND event_type = 'WIKI_PAGE_INDEX'
                        """, Integer.class, detail.page().id());
                assertThat(count).isEqualTo(1);
            } finally {
                if (pageId != null) {
                    jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", pageId);
                }
                jdbc.update("DELETE FROM wiki_space WHERE id = ?", space.id());
            }
        });
    }
}
