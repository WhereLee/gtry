package com.wherelee.helpdesk.wiki;

import java.time.Instant;
import java.util.UUID;

public record WikiHit(
        UUID sectionId,
        UUID pageId,
        String pageTitle,
        String headingPath,
        String content,
        String visibility,
        Instant updatedAt,
        double score,
        double fusedScore,
        double rerankScore
) {
}
