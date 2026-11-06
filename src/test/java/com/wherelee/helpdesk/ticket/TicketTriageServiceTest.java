package com.wherelee.helpdesk.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketTriageServiceTest {

    private final UUID agentId = UUID.randomUUID();
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketTriageService service = new TicketTriageService(tickets);

    @BeforeEach
    void setUp() {
        when(tickets.findLeastLoadedAgent(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(agentId));
    }

    @Test
    void routesRefundQuestionsToReturns() {
        TicketTriageService.TriageResult result = service.triage(
                "耳机退货", "订单金额超过 160 元，想退货，需要注意什么？", UUID.randomUUID());

        assertThat(result.category()).isEqualTo("RETURNS");
        assertThat(result.priority()).isEqualTo("HIGH");
        assertThat(result.assigneeId()).isEqualTo(agentId);
        assertThat(result.reason()).contains("退货");
    }

    @Test
    void escalatesComplaintsToUrgent() {
        TicketTriageService.TriageResult result = service.triage(
                "投诉客服态度", "我要投诉，要求主管处理并赔偿", UUID.randomUUID());

        assertThat(result.category()).isEqualTo("COMPLAINT");
        assertThat(result.priority()).isEqualTo("URGENT");
    }

    @Test
    void fallsBackToOtherWhenNoRuleMatches() {
        TicketTriageService.TriageResult result = service.triage("你好", "想了解一下", UUID.randomUUID());

        assertThat(result.category()).isEqualTo("OTHER");
        assertThat(result.priority()).isEqualTo("LOW");
    }

    @Test
    void validatesCategoryAndPriorityCodes() {
        assertThat(service.isValidCategory("returns")).isTrue();
        assertThat(service.isValidCategory("unknown")).isFalse();
        assertThat(service.isValidPriority("urgent")).isTrue();
        assertThat(service.isValidPriority("p0")).isFalse();
    }
}
