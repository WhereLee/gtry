package com.wherelee.helpdesk.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "helpdesk.rerank.enabled", havingValue = "false")
public class NoopReranker implements Reranker {

    @Override
    public double score(String query, String passage) {
        return 0;
    }
}
