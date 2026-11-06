package com.wherelee.helpdesk.tenant;

import com.wherelee.helpdesk.ticket.Ticket;
import com.wherelee.helpdesk.ticket.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the local PostgreSQL test database. Enable with HELPDESK_IT=true
 * so a plain `mvn test` does not require infrastructure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "helpdesk.llm.provider=mock",
        "helpdesk.embedding.provider=mock",
        "helpdesk.rerank.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "HELPDESK_IT", matches = "true")
class TenantIsolationTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CLOUDWISE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    private TicketRepository tickets;

    @Test
    void tenantCannotReadAnotherTenantsTicket() {
        Ticket ticket = TenantContext.callWith(
                TenantScope.tenant(ACME),
                () -> tickets.create(ACME, "isolation-test", "only acme can see this",
                        "NORMAL", "OTHER", "RULE", "test", null)
        );
        try {
            assertThat(TenantContext.callWith(TenantScope.tenant(ACME),
                    () -> tickets.findById(ticket.id()))).isPresent();
            assertThat(TenantContext.callWith(TenantScope.tenant(CLOUDWISE),
                    () -> tickets.findById(ticket.id()))).isEmpty();
            assertThat(TenantContext.callWith(TenantScope.tenant(CLOUDWISE),
                    () -> tickets.list(null, 100))).isEmpty();
        } finally {
            TenantContext.runWith(TenantScope.tenant(ACME), () -> tickets.delete(ticket.id()));
        }
    }

    @Test
    void writeForAnotherTenantIsRejectedByRls() {
        assertThatThrownBy(() -> TenantContext.callWith(
                TenantScope.tenant(ACME),
                () -> tickets.create(CLOUDWISE, "cross-tenant", "should be rejected",
                        "NORMAL", "OTHER", "RULE", "test", null)
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void noTenantContextSeesNothing() {
        assertThat(tickets.list(null, 10)).isEmpty();
    }
}
