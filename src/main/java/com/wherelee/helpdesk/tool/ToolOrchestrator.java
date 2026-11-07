package com.wherelee.helpdesk.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrator.class);

    private final OrderLookupTool orderLookup;
    private final ToolCallLogRepository logRepository;
    private final ObjectMapper objectMapper;
    private final Counter successCounter;
    private final Counter failureCounter;

    public ToolOrchestrator(
            OrderLookupTool orderLookup,
            ToolCallLogRepository logRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.orderLookup = orderLookup;
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
        this.successCounter = meterRegistry.counter(
                "helpdesk_tool_call_total", "tool", "order_lookup", "status", "success");
        this.failureCounter = meterRegistry.counter(
                "helpdesk_tool_call_total", "tool", "order_lookup", "status", "failure");
    }

    public String executeOrderLookup(UUID ticketId, String question) {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            return "";
        }
        Optional<OrderLookupTool.OrderInfo> order = orderLookup.lookup(question);
        if (order.isEmpty()) {
            return "";
        }
        OrderLookupTool.OrderInfo info = order.get();
        try {
            String args = objectMapper.writeValueAsString(Map.of("orderNo", info.orderNo()));
            String result = objectMapper.writeValueAsString(Map.of(
                    "orderNo", info.orderNo(),
                    "status", info.status(),
                    "item", info.item(),
                    "amount", info.amount(),
                    "returnable", info.returnable(),
                    "purchasedAt", info.purchasedAt()
            ));
            logRepository.insert(scope.tenantId(), ticketId, "order_lookup", args, result, "SUCCESS");
            successCounter.increment();
            return "订单号：" + info.orderNo()
                    + "；状态：" + info.status()
                    + "；商品：" + info.item()
                    + "；金额：" + info.amount()
                    + "；可退货：" + (info.returnable() ? "是" : "否")
                    + "；购买日期：" + info.purchasedAt();
        } catch (Exception ex) {
            failureCounter.increment();
            log.warn("helpdesk_tool_call_failed tool=order_lookup ticket={} error={}",
                    ticketId, ex.getMessage());
            return "";
        }
    }
}
