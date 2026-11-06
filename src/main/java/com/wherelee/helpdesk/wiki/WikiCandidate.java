package com.wherelee.helpdesk.wiki;

import java.time.Instant;
import java.util.UUID;

public record WikiCandidate(
        UUID chunkId,
        UUID sectionId,
        UUID pageId,
        String pageTitle,
        String headingPath,
        String content,
        String visibility,
        Instant updatedAt,
        double score,
        int rank
) {
    public WikiCandidate withScore(double newScore) {
        return new WikiCandidate(
                chunkId, sectionId, pageId, pageTitle, headingPath,
                content, visibility, updatedAt, newScore, rank
        );
    }
}
