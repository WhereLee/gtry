package com.wherelee.helpdesk.ai;

import java.util.function.Consumer;

public interface LlmClient {

    /**
     * Streams the answer token by token and returns the token usage for billing.
     */
    int stream(LlmRequest request, Consumer<String> onToken);
}
