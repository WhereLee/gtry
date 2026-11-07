package com.wherelee.helpdesk.wiki;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WikiDtos {

    private WikiDtos() {
    }

    public record SearchRequest(
            @NotBlank @Size(max = 2000) String query,
            Integer topK,
            Boolean includeInternal
    ) {
    }

    public record HitView(
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
        public static HitView of(WikiHit hit) {
            return new HitView(
                    hit.sectionId(), hit.pageId(), hit.pageTitle(), hit.headingPath(),
                    hit.content(), hit.visibility(), hit.updatedAt(),
                    hit.score(), hit.fusedScore(), hit.rerankScore()
            );
        }
    }

    public record SearchResponse(String traceId, List<HitView> hits, WikiSearchResult.Stats stats) {
        public static SearchResponse of(WikiSearchResult result) {
            return new SearchResponse(
                    result.traceId(),
                    result.hits().stream().map(HitView::of).toList(),
                    result.stats()
            );
        }
    }

    public record SpaceView(UUID id, String slug, String name, String description) {
        public static SpaceView of(WikiSpace space) {
            return new SpaceView(space.id(), space.slug(), space.name(), space.description());
        }
    }

    public record PageView(
            UUID id,
            UUID spaceId,
            String spaceName,
            String slug,
            String title,
            String visibility,
            String status,
            Instant lastReviewedAt,
            Instant updatedAt
    ) {
        public static PageView of(WikiPage page) {
            return new PageView(
                    page.id(), page.spaceId(), page.spaceName(), page.slug(), page.title(),
                    page.visibility(), page.status(), page.lastReviewedAt(), page.updatedAt()
            );
        }
    }

    public record SectionView(UUID id, int seq, String headingPath, String content) {
        public static SectionView of(WikiSection section) {
            return new SectionView(section.id(), section.seq(), section.headingPath(), section.content());
        }
    }

    public record PageDetail(PageView page, List<SectionView> sections) {
    }

    public record PageVersionView(
            UUID id,
            int version,
            String title,
            String visibility,
            String status,
            UUID createdBy,
            Instant createdAt
    ) {
        public static PageVersionView of(WikiPageVersion version) {
            return new PageVersionView(
                    version.id(), version.version(), version.title(), version.visibility(),
                    version.status(), version.createdBy(), version.createdAt());
        }
    }

    public record PageVersionDetail(
            PageVersionView version,
            List<WikiPageDtos.SectionInput> sections
    ) {
    }

    public record IndexStatus(
            long pages, long sections, long chunks, long questions,
            long pendingIndexEvents, long failedIndexEvents
    ) {
    }
}
