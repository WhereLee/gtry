package com.wherelee.helpdesk.notification;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID tenantId,
        UUID userId,
        String type,
        String title,
        String body,
        String targetType,
        String targetId,
        Instant readAt,
        Instant createdAt
) {
}
