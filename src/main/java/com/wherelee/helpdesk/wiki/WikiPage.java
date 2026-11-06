package com.wherelee.helpdesk.wiki;

import java.time.Instant;
import java.util.UUID;

public record WikiPage(
        UUID id,
        UUID tenantId,
        UUID spaceId,
        String spaceName,
        String slug,
        String title,
        String visibility,
        String status,
        UUID ownerId,
        Instant lastReviewedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
