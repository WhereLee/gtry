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
        String category,
        String triageSource,
        String triageReason,
        String customerName,
        String customerEmail,
        Integer csatRating,
        String csatComment,
        Instant csatAt,
        Instant firstResponseAt,
        Instant resolvedAt,
        Instant slaDueAt,
        boolean slaBreached,
        UUID assigneeId,
        Instant createdAt,
        Instant updatedAt
) {
}
