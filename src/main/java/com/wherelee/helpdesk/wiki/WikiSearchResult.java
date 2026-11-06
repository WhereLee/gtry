package com.wherelee.helpdesk.wiki;

import java.util.List;

public record WikiSearchResult(String traceId, List<WikiHit> hits, Stats stats) {

    public record Stats(
            int vectorCount,
            int ftsCount,
            int fusedCount,
            long embedMs,
            long vectorMs,
            long ftsMs,
            long rerankMs,
            long totalMs
    ) {
    }
}
