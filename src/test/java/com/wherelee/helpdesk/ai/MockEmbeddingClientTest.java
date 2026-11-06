package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingClientTest {

    private final MockEmbeddingClient client = new MockEmbeddingClient(new HelpdeskProperties());

    @Test
    void isDeterministic() {
        float[] first = client.embed("退货退款怎么处理");
        float[] second = client.embed("退货退款怎么处理");

        assertThat(first).containsExactly(second);
        assertThat(first).hasSize(client.dimension());
    }

    @Test
    void lexicalOverlapScoresHigherThanUnrelatedText() {
        float[] query = client.embed("退货退款");
        float[] related = client.embed("退货与退款政策：7 天无理由退货");
        float[] unrelated = client.embed("套餐计费与账号权限");

        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated));
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
