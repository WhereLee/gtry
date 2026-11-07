package com.wherelee.helpdesk.ai;

import java.util.List;

public record LlmRequest(
        String model,
        String systemPrompt,
        String userPrompt,
        List<String> contextSnippets
) {
}
