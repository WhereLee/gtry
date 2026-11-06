package com.wherelee.helpdesk.ai;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.wherelee.helpdesk.config.HelpdeskProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-encoder reranker (bge-reranker-base int8). Vector search is recall-oriented;
 * the cross-encoder scores each (query, passage) pair and fixes the near-ties that
 * bi-encoders produce on short Chinese text.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.rerank.enabled", havingValue = "true", matchIfMissing = true)
public class OnnxReranker implements Reranker {

    private final HelpdeskProperties.Rerank config;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean hasTokenTypeIds;

    public OnnxReranker(HelpdeskProperties properties) throws Exception {
        this.config = properties.getRerank();
        Path modelDir = Path.of(config.getModelPath());
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            this.session = environment.createSession(modelDir.resolve("model.onnx").toString(), options);
        }
        this.hasTokenTypeIds = session.getInputNames().contains("token_type_ids");
        this.tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(modelDir)
                .optMaxLength(config.getMaxLength())
                .optTruncation(true)
                .optPadding(false)
                .build();
    }

    @Override
    public double score(String query, String passage) {
        Encoding encoding = tokenizer.encode(query, passage);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] types = encoding.getTypeIds();
        long[] shape = {1, ids.length};
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
             OnnxTensor typesTensor = hasTokenTypeIds
                     ? OnnxTensor.createTensor(environment, LongBuffer.wrap(types), shape)
                     : null) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            if (typesTensor != null) {
                inputs.put("token_type_ids", typesTensor);
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                return sigmoid(logits[0][0]);
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("onnx rerank failed", ex);
        }
    }

    private double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }

    @PreDestroy
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // shutdown best effort
        }
    }
}
