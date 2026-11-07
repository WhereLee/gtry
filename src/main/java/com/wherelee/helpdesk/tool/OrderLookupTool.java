package com.wherelee.helpdesk.tool;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderLookupTool {

    private static final Pattern ORDER_NUMBER = Pattern.compile(
            "(?i)(?:订单号|order\\s*(?:no|id)?)[:：\\s]*([A-Z0-9-]{6,})");

    public Optional<OrderInfo> lookup(String question) {
        Matcher matcher = ORDER_NUMBER.matcher(question == null ? "" : question);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String orderNo = matcher.group(1).toUpperCase();
        // Demo integration: a real deployment would call the tenant's order service.
        return Optional.of(new OrderInfo(
                orderNo, "已签收", "无线耳机", 199.00, true, "2026-10-20"));
    }

    public record OrderInfo(
            String orderNo,
            String status,
            String item,
            double amount,
            boolean returnable,
            String purchasedAt
    ) {
    }
}
