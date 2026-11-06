package com.wherelee.helpdesk.ticket;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class AiFeedbackTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void adoptingAiSuggestionCreatesPublicAgentReplyAndRecordsFeedback() {
        UUID ticketId = TenantContext.callWith(TenantScope.tenant(ACME), () -> {
            TicketDtos.TicketView ticket = ticketService.create(
                    "反馈测试", "耳机想退货", null, "张三", "zhangsan@example.com");
            TicketMessage ai = tickets.addMessage(
                    ACME, ticket.id(), "AI", null, "建议回复：7 天无理由退货。", "[]", 10, "INTERNAL");
            TicketDtos.FeedbackView feedback = ticketService.feedback(
                    ticket.id(), ai.id(), new TicketDtos.FeedbackRequest("ADOPT", null));
            assertThat(feedback.action()).isEqualTo("ADOPT");
            assertThat(tickets.listPublicMessages(ticket.id()))
                    .extracting(TicketMessage::content)
                    .contains("建议回复：7 天无理由退货。");
            assertThat(tickets.findById(ticket.id()).orElseThrow().status()).isEqualTo("PENDING");
            return ticket.id();
        });
        try {
            Integer count = TenantContext.callWith(TenantScope.tenant(ACME), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM ai_feedback WHERE ticket_id = ? AND action = 'ADOPT'",
                    Integer.class, ticketId));
            assertThat(count).isEqualTo(1);
        } finally {
            TenantContext.runWith(TenantScope.tenant(ACME), () -> tickets.delete(ticketId));
        }
    }

    @Test
    void editingAiSuggestionUsesEditedText() {
        UUID ticketId = TenantContext.callWith(TenantScope.tenant(ACME), () -> {
            TicketDtos.TicketView ticket = ticketService.create(
                    "编辑测试", "发票怎么开", null, null, null);
            TicketMessage ai = tickets.addMessage(
                    ACME, ticket.id(), "AI", null, "原始建议", "[]", 10, "INTERNAL");
            ticketService.feedback(
                    ticket.id(), ai.id(), new TicketDtos.FeedbackRequest("EDIT", "修改后的建议"));
            assertThat(tickets.listPublicMessages(ticket.id()))
                    .extracting(TicketMessage::content)
                    .contains("修改后的建议")
                    .doesNotContain("原始建议");
            return ticket.id();
        });
        TenantContext.runWith(TenantScope.tenant(ACME), () -> tickets.delete(ticketId));
    }
}
