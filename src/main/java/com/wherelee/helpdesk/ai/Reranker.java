package com.wherelee.helpdesk.ai;

public interface Reranker {

    /**
     * Cross-encoder relevance in [0, 1]. Higher is better.
     */
    double score(String query, String passage);
}
