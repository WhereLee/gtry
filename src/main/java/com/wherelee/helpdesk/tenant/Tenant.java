package com.wherelee.helpdesk.tenant;

import java.util.UUID;

public record Tenant(
        UUID id,
        String slug,
        String name,
        String plan,
        String status,
        long monthlyTokenBudget
) {
}
