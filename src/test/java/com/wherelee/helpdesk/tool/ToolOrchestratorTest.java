package com.wherelee.helpdesk.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolOrchestratorTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TICKET = UUID.randomUUID();

    private final ToolCallLogRepository repository = mock(ToolCallLogRepository.class);
    private final ToolOrchestrator orchestrator = new ToolOrchestrator(
            new OrderLookupTool(), repository, new ObjectMapper(), new SimpleMeterRegistry());

    @Test
    void logsAndFormatsOrderLookup() {
        String result = TenantContext.callWith(TenantScope.tenant(ACME),
                () -> orchestrator.executeOrderLookup(TICKET, "订单号 ORD123456，想退货"));

        assertThat(result).contains("ORD123456", "已签收", "可退货：是");
        verify(repository).insert(eq(ACME), eq(TICKET), eq("order_lookup"),
                anyString(), anyString(), eq("SUCCESS"));
    }
}
