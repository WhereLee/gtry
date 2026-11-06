package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.ai.EmbeddingClient;
import com.wherelee.helpdesk.ai.Reranker;
import com.wherelee.helpdesk.config.HelpdeskProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class WikiSearchService {

    private static final Logger log = LoggerFactory.getLogger(WikiSearchService.class);

    private final WikiRepository repository;
    private final EmbeddingClient embeddingClient;
    private final Reranker reranker;
    private final HelpdeskProperties.Wiki config;
    private final Timer embedTimer;
    private final Timer vectorTimer;
    private final Timer ftsTimer;
    private final Timer rerankTimer;
    private final Counter emptyCounter;

    public WikiSearchService(
            WikiRepository repository,
            EmbeddingClient embeddingClient,
            Reranker reranker,
            HelpdeskProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.reranker = reranker;
        this.config = properties.getWiki();
        this.embedTimer = Timer.builder("helpdesk_embedding_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.vectorTimer = Timer.builder("helpdesk_vector_search_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.ftsTimer = Timer.builder("helpdesk_fts_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.rerankTimer = Timer.builder("helpdesk_rerank_duration")
                .publishPercentiles(0.5, 0.95, 0.99).register(meterRegistry);
        this.emptyCounter = meterRegistry.counter("helpdesk_search_total", "result", "empty");
    }

    public WikiSearchResult search(String query, int topK, boolean includeInternal) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        long started = System.nanoTime();

        long t = System.nanoTime();
        String embedding = embeddingClient.embedQueryAsVector(query);
        long embedMs = elapsed(t);
        embedTimer.record(embedMs, TimeUnit.MILLISECONDS);

        t = System.nanoTime();
        List<WikiCandidate> vector = repository.searchVector(
                embedding, config.getVectorCandidates(), includeInternal);
        long vectorMs = elapsed(t);
        vectorTimer.record(vectorMs, TimeUnit.MILLISECONDS);

        t = System.nanoTime();
        List<WikiCandidate> fts = List.of();
        try {
            String tsQuery = ChineseText.tsQuery(query);
            if (!tsQuery.isBlank()) {
                fts = repository.searchFullText(tsQuery, config.getFtsCandidates(), includeInternal);
            }
        } catch (Exception ex) {
            log.warn("helpdesk_search_fts_failed traceId={} error={}", traceId, ex.getMessage());
        }
        long ftsMs = elapsed(t);
        ftsTimer.record(ftsMs, TimeUnit.MILLISECONDS);

        Map<UUID, Fused> fused = new LinkedHashMap<>();
        addRanked(fused, vector, traceId, "vector");
        addRanked(fused, fts, traceId, "fts");

        Instant now = Instant.now();
        List<Fused> ranked = new ArrayList<>(fused.values());
        for (Fused item : ranked) {
            item.score += config.getFreshnessBoost() * freshness(item.candidate.updatedAt(), now);
        }
        ranked.sort(Comparator.comparingDouble((Fused item) -> item.score).reversed());

        List<Fused> pool = ranked.stream().limit(config.getRerankCandidates()).toList();
        t = System.nanoTime();
        for (Fused item : pool) {
            try {
                // The heading path carries product/space/amount, which is often the
                // only discriminator between near-identical pages.
                item.rerankScore = reranker.score(
                        query, item.candidate.headingPath() + "\n" + item.candidate.content());
            } catch (Exception ex) {
                item.rerankScore = 0;
                log.warn("helpdesk_search_rerank_failed traceId={} sectionId={} error={}",
                        traceId, item.candidate.sectionId(), ex.getMessage());
            }
        }
        long rerankMs = elapsed(t);
        rerankTimer.record(rerankMs, TimeUnit.MILLISECONDS);

        List<Fused> ordered = pool.stream()
                .sorted(Comparator.comparingDouble(
                                (Fused item) -> item.rerankScore > 0 ? item.rerankScore : item.score)
                        .thenComparingDouble(item -> item.score)
                        .reversed())
                .limit(topK)
                .toList();

        List<WikiHit> hits = ordered.stream().map(item -> new WikiHit(
                item.candidate.sectionId(),
                item.candidate.pageId(),
                item.candidate.pageTitle(),
                item.candidate.headingPath(),
                item.candidate.content(),
                item.candidate.visibility(),
                item.candidate.updatedAt(),
                round(item.rerankScore > 0 ? item.rerankScore : item.score),
                round(item.score),
                round(item.rerankScore)
        )).toList();

        long totalMs = elapsed(started);
        if (hits.isEmpty()) {
            emptyCounter.increment();
        }
        log.info("helpdesk_search traceId={} queryHash={} includeInternal={} vector={} fts={} fused={} "
                        + "embedMs={} vectorMs={} ftsMs={} rerankMs={} totalMs={} top={}",
                traceId,
                Integer.toHexString(query.hashCode()),
                includeInternal,
                vector.size(),
                fts.size(),
                fused.size(),
                embedMs, vectorMs, ftsMs, rerankMs, totalMs,
                hits.stream().limit(3)
                        .map(hit -> hit.sectionId().toString().substring(0, 8) + ":" + hit.score())
                        .toList());

        return new WikiSearchResult(traceId, hits, new WikiSearchResult.Stats(
                vector.size(), fts.size(), fused.size(), embedMs, vectorMs, ftsMs, rerankMs, totalMs));
    }

    private void addRanked(Map<UUID, Fused> fused, List<WikiCandidate> candidates, String traceId, String source) {
        int rank = 1;
        for (WikiCandidate candidate : candidates) {
            double contribution = 1.0 / (config.getRrfK() + rank);
            Fused item = fused.computeIfAbsent(candidate.chunkId(), ignored -> new Fused(candidate));
            item.score += contribution;
            rank++;
        }
        log.debug("helpdesk_search_candidates traceId={} source={} count={}", traceId, source, candidates.size());
    }

    private double freshness(Instant updatedAt, Instant now) {
        if (updatedAt == null) {
            return 0;
        }
        long ageDays = Math.max(0, Duration.between(updatedAt, now).toDays());
        return Math.exp(-ageDays / 365.0);
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static final class Fused {
        private final WikiCandidate candidate;
        private double score;
        private double rerankScore;

        private Fused(WikiCandidate candidate) {
            this.candidate = candidate;
        }
    }
}
