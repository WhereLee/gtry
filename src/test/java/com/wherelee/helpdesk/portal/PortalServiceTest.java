package com.wherelee.helpdesk.portal;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.ticket.TicketDtos;
import com.wherelee.helpdesk.ticket.TicketRepository;
import com.wherelee.helpdesk.ticket.TicketService;
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
class PortalServiceTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private PortalService portalService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository tickets;

    @Test
    void customerCanSubmitTrackReplyAndRateWithoutSeeingInternalNotes() {
        PortalDtos.CreateTicketResponse created = portalService.create(
                "acme-demo-key",
                new PortalDtos.CreateTicketRequest(
                        "耳机退货咨询", "订单金额超过 160 元，想退货", "张三", "zhangsan@example.com", "")
        );
        try {
            assertThat(created.token()).isNotBlank();
            assertThat(created.ticketId()).isNotNull();

            PortalDtos.PublicTicketView initial = portalService.get(created.ticketId(), created.token());
            assertThat(initial.subject()).isEqualTo("耳机退货咨询");
            assertThat(initial.messages()).extracting(PortalDtos.PublicMessageView::content)
                    .contains("订单金额超过 160 元，想退货");

            TenantContext.runWith(TenantScope.tenant(ACME), () -> ticketService.addInternalNote(
                    created.ticketId(), "内部备注：客户情绪稳定，可优先处理"));
            PortalDtos.PublicTicketView afterNote = portalService.get(created.ticketId(), created.token());
            assertThat(afterNote.messages()).extracting(PortalDtos.PublicMessageView::content)
                    .doesNotContain("内部备注：客户情绪稳定，可优先处理");

            PortalDtos.PublicTicketView afterReply = portalService.reply(
                    created.ticketId(), created.token(), "补充：我已经把商品寄回了");
            assertThat(afterReply.status()).isEqualTo("OPEN");
            assertThat(afterReply.messages()).extracting(PortalDtos.PublicMessageView::content)
                    .contains("补充：我已经把商品寄回了");

            TenantContext.runWith(TenantScope.tenant(ACME),
                    () -> ticketService.updateStatus(created.ticketId(), "CLOSED"));
            portalService.csat(created.ticketId(), created.token(), 5, "处理很快");
            PortalDtos.PublicTicketView rated = portalService.get(created.ticketId(), created.token());
            assertThat(rated.csatRating()).isEqualTo(5);
            assertThat(rated.csatComment()).isEqualTo("处理很快");
        } finally {
            TenantContext.runWith(TenantScope.tenant(ACME), () -> tickets.delete(created.ticketId()));
        }
    }

    @Test
    void rejectsUnknownChannel() {
        assertThat(catchThrowable(() -> portalService.create(
                "not-a-channel",
                new PortalDtos.CreateTicketRequest("x", "y", null, null, "")
        ))).isNotNull();
    }

    private Throwable catchThrowable(ThrowingCallable callable) {
        try {
            callable.call();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call() throws Exception;
    }
}
