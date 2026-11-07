package com.wherelee.helpdesk.wiki;

import java.time.Instant;
import java.util.UUID;

public record WikiPageVersion(
        UUID id,
        UUID pageId,
        int version,
        String title,
        String visibility,
        String status,
        String sectionsJson,
        UUID createdBy,
        Instant createdAt
) {
}
