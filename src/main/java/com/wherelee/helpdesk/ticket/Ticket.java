package com.wherelee.helpdesk.ticket;

import java.time.Instant;
import java.util.UUID;

public record Ticket(
        UUID id,
        UUID tenantId,
        UUID channelId,
        String externalRef,
        String subject,
        String description,
        String status,
        String priority,
        UUID assigneeId,
        Instant createdAt,
        Instant updatedAt
) {
}
