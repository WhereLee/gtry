package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Retrieval quality on the synthetic wiki: recall@5, MRR@10 and nDCG@10. Each
 * question is generated from one section, so the ground truth is exact.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final WikiRepository repository;
    private final WikiSearchService searchService;
    private final TenantRepository tenants;
    private final JobStatus status = new JobStatus();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "helpdesk-eval");
        thread.setDaemon(true);
        return thread;
    });

    public EvalService(WikiRepository repository, WikiSearchService searchService, TenantRepository tenants) {
        this.repository = repository;
        this.searchService = searchService;
        this.tenants = tenants;
    }

    public JobStatus.Snapshot start(int samplePerTenant, String kind) {
        if (status.snapshot().state() == JobStatus.State.RUNNING) {
            return status.snapshot();
        }
        int tenantCount = (int) tenants.findAll().stream()
                .filter(tenant -> !"platform".equals(tenant.slug()))
                .count();
        status.start(samplePerTenant * Math.max(1, tenantCount));
        executor.submit(() -> run(samplePerTenant, kind));
        return status.snapshot();
    }

    public JobStatus.Snapshot status() {
        return status.snapshot();
    }

    private void run(int samplePerTenant, String kind) {
        try {
            List<Map<String, Object>> tenantResults = new ArrayList<>();
            List<Map<String, Object>> misses = new ArrayList<>();
            int total = 0;
            double recallWeighted = 0;
            double mrrWeighted = 0;
            double ndcgWeighted = 0;
            long totalMs = 0;

            for (Tenant tenant : tenants.findAll()) {
                if ("platform".equals(tenant.slug())) {
                    continue;
                }
                Map<String, Object> result = TenantContext.callWith(
                        TenantScope.tenant(tenant.id()),
                        () -> evalTenant(tenant, samplePerTenant, kind, misses)
                );
                tenantResults.add(result);
                int sampleCount = (int) result.get("sample");
                total += sampleCount;
                recallWeighted += (double) result.get("recallAt5") * sampleCount;
                mrrWeighted += (double) result.get("mrrAt10") * sampleCount;
                ndcgWeighted += (double) result.get("ndcgAt10") * sampleCount;
                totalMs += (long) result.get("avgMs") * sampleCount;
                status.progress(total);
            }

            Map<String, Object> overall = new LinkedHashMap<>();
            overall.put("sample", total);
            overall.put("recallAt5", round(recallWeighted / Math.max(1, total)));
            overall.put("mrrAt10", round(mrrWeighted / Math.max(1, total)));
            overall.put("ndcgAt10", round(ndcgWeighted / Math.max(1, total)));
            overall.put("avgMs", totalMs / Math.max(1, total));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("overall", overall);
            payload.put("kind", kind);
            payload.put("tenants", tenantResults);
            payload.put("misses", misses.stream().limit(20).toList());
            status.done(payload);
            log.info("helpdesk_eval_done overall={}", overall);
        } catch (Exception ex) {
            status.fail(ex.getMessage());
            log.error("helpdesk_eval_failed", ex);
        }
    }

    private Map<String, Object> evalTenant(
            Tenant tenant, int sample, String kind, List<Map<String, Object>> misses) {
        List<WikiRepository.EvalQuestionRow> questions = repository.sampleQuestions(sample, kind);
        int recallHits = 0;
        double reciprocal = 0;
        double ndcg = 0;
        long totalMs = 0;

        for (WikiRepository.EvalQuestionRow question : questions) {
            WikiSearchResult result = searchService.search(question.question(), 10, true);
            totalMs += result.stats().totalMs();
            int rank = rankOfPage(result.hits(), question.pageId());
            if (rank > 0) {
                if (rank <= 5) {
                    recallHits++;
                }
                reciprocal += 1.0 / rank;
                ndcg += 1.0 / (Math.log(rank + 1) / Math.log(2));
            } else if (misses.size() < 20) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("tenant", tenant.slug());
                miss.put("question", question.question());
                miss.put("expectedPageId", question.pageId().toString());
                miss.put("topHit", result.hits().isEmpty() ? null : Map.of(
                        "title", result.hits().get(0).pageTitle(),
                        "heading", result.hits().get(0).headingPath(),
                        "score", result.hits().get(0).score()
                ));
                misses.add(miss);
            }
        }

        int size = Math.max(1, questions.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant", tenant.slug());
        result.put("kind", kind);
        result.put("sample", questions.size());
        result.put("recallAt5", round((double) recallHits / size));
        result.put("mrrAt10", round(reciprocal / size));
        result.put("ndcgAt10", round(ndcg / size));
        result.put("avgMs", totalMs / size);
        log.info("helpdesk_eval_tenant tenant={} sample={} recallAt5={} mrrAt10={} ndcgAt10={}",
                tenant.slug(), questions.size(), result.get("recallAt5"),
                result.get("mrrAt10"), result.get("ndcgAt10"));
        return result;
    }

    private int rankOfPage(List<WikiHit> hits, java.util.UUID pageId) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).pageId().equals(pageId)) {
                return i + 1;
            }
        }
        return 0;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
