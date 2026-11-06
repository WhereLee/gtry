package com.wherelee.helpdesk.events;

import java.util.UUID;

public record AiFeedbackAcceptedEvent(
        UUID tenantId,
        UUID ticketId,
        UUID messageId,
        String action,
        String question,
        String citationsJson
) {
}
