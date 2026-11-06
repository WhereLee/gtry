package com.wherelee.helpdesk.outbox;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
class OutboxRepositoryTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private OutboxPublisher outboxPublisher;

    @Test
    void claimsPublishesAndBacksOffFailures() {
        TenantContext.runWith(TenantScope.tenant(ACME), () -> {
            jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", ACME);
            UUID pageId = UUID.randomUUID();
            outbox.insert(ACME, "wiki_page", pageId, "WIKI_PAGE_INDEX",
                    "{\"pageId\":\"" + pageId + "\"}");

            List<OutboxRepository.OutboxEvent> claimed = outbox.claimBatch(10);
            assertThat(claimed).hasSize(1);
            outbox.markPublished(claimed.get(0).id());
            assertThat(outbox.countByStatus("PUBLISHED")).isEqualTo(1);

            UUID secondPage = UUID.randomUUID();
            outbox.insert(ACME, "wiki_page", secondPage, "WIKI_PAGE_INDEX",
                    "{\"pageId\":\"" + secondPage + "\"}");
            List<OutboxRepository.OutboxEvent> second = outbox.claimBatch(10);
            assertThat(second).hasSize(1);
            outbox.markFailed(second.get(0).id(), "redis unavailable");

            Integer attempts = jdbc.queryForObject(
                    "SELECT attempts FROM outbox_event WHERE id = ?",
                    Integer.class, second.get(0).id());
            String status = jdbc.queryForObject(
                    "SELECT status FROM outbox_event WHERE id = ?",
                    String.class, second.get(0).id());
            assertThat(attempts).isEqualTo(1);
            assertThat(status).isEqualTo("PENDING");
            jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", ACME);
        });
    }
}
