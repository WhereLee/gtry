package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic feature-hashing embedding: ASCII words plus Chinese unigrams and
 * bigrams hashed into a fixed-size vector. It is not a semantic model, but it gives
 * stable lexical similarity so RAG works end-to-end without an embedding API.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.embedding.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingClient implements EmbeddingClient {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z0-9]+");

    private final int dimension;

    public MockEmbeddingClient(HelpdeskProperties properties) {
        this.dimension = properties.getEmbedding().getDimension();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        List<String> tokens = tokenize(text == null ? "" : text);
        for (String token : tokens) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, dimension);
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[index] += sign;
        }
        normalize(vector);
        return vector;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ASCII_WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        String cjk = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cjk.length(); i++) {
            tokens.add(cjk.substring(i, i + 1));
            if (i + 1 < cjk.length()) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
    }
}
