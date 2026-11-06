package com.wherelee.helpdesk.ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketMessage(
        UUID id,
        UUID tenantId,
        UUID ticketId,
        String authorType,
        UUID authorId,
        String content,
        String citationsJson,
        int tokens,
        Instant createdAt
) {
}
