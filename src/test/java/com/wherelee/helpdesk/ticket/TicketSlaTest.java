package com.wherelee.helpdesk.ticket;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
class TicketSlaTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository tickets;

    @Test
    void setsSlaFirstResponseAndResolution() {
        TenantScope admin = new TenantScope(ACME, ADMIN, "admin@acme.test", "TENANT_ADMIN", false);
        UUID ticketId = TenantContext.callWith(admin, () -> {
            TicketDtos.TicketView ticket = ticketService.create(
                    "SLA 测试", "订单想退货", null, null, null);
            assertThat(ticket.slaDueAt()).isNotNull();
            assertThat(ticket.firstResponseAt()).isNull();

            ticketService.addMessage(ticket.id(), "已收到，马上处理");
            TicketDtos.TicketView replied = ticketService.get(ticket.id()).ticket();
            assertThat(replied.firstResponseAt()).isNotNull();

            ticketService.updateStatus(ticket.id(), "CLOSED");
            TicketDtos.TicketView closed = ticketService.get(ticket.id()).ticket();
            assertThat(closed.resolvedAt()).isNotNull();
            return ticket.id();
        });
        TenantContext.runWith(admin, () -> tickets.delete(ticketId));
    }
}
