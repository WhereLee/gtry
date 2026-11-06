package com.wherelee.helpdesk.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic stand-in for the real model. It answers from the retrieved
 * snippets so the whole RAG path (retrieval -> prompt -> stream -> persist) can be
 * exercised without an API key.
 */
@Component
@ConditionalOnProperty(name = "helpdesk.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public int stream(LlmRequest request, Consumer<String> onToken) {
        String answer = compose(request);
        for (int i = 0; i < answer.length(); i++) {
            onToken.accept(String.valueOf(answer.charAt(i)));
            if (i % 8 == 7) {
                sleep(8);
            }
        }
        return Math.max(1, answer.length() / 2);
    }

    private String compose(LlmRequest request) {
        List<String> snippets = request.contextSnippets();
        StringBuilder sb = new StringBuilder();
        if (snippets == null || snippets.isEmpty()) {
            sb.append("抱歉，知识库里没有找到直接相关的资料。建议把这个工单转给人工客服，")
                    .append("并补充订单号或账号信息，方便进一步核实。");
            return sb.toString();
        }
        sb.append("根据知识库中的资料，整理如下：\n\n");
        int used = 0;
        for (String snippet : snippets) {
            if (used >= 2) {
                break;
            }
            String clean = snippet.replaceAll("\\s+", " ").trim();
            if (clean.length() > 220) {
                clean = clean.substring(0, 220) + "…";
            }
            sb.append("• [").append(used + 1).append("] ").append(clean).append('\n');
            used++;
        }
        sb.append("\n以上信息供参考。如果和你的实际情况不一致，可以转人工客服继续处理。");
        return sb.toString();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
