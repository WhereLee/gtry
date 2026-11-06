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
import java.util.Map;

/**
 * In-process BGE embedding via ONNX Runtime. The model is the local int8
 * bge-base-zh-v1.5 export: CLS pooling over last_hidden_state, then L2 normalize.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.embedding.provider", havingValue = "onnx")
public class OnnxEmbeddingClient implements EmbeddingClient {

    private final HelpdeskProperties.Embedding config;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean hasTokenTypeIds;

    public OnnxEmbeddingClient(HelpdeskProperties properties) throws Exception {
        this.config = properties.getEmbedding();
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
    public int dimension() {
        return config.getDimension();
    }

    @Override
    public float[] embed(String text) {
        Encoding encoding = tokenizer.encode(text == null ? "" : text);
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
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[] vector = hidden[0][0].clone();
                normalize(vector);
                if (vector.length != config.getDimension()) {
                    throw new IllegalStateException("embedding dimension mismatch: model="
                            + vector.length + " configured=" + config.getDimension());
                }
                return vector;
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("onnx embedding failed", ex);
        }
    }

    @Override
    public float[] embedQuery(String text) {
        String prefix = config.getQueryPrefix();
        return embed(prefix == null || prefix.isBlank() ? text : prefix + text);
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

    @PreDestroy
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // shutdown best effort
        }
    }
}
