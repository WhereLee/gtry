package com.wherelee.helpdesk.ai;

/**
 * The SSE client went away while tokens were being streamed. Treated separately
 * from provider errors so the partial answer can still be persisted.
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(Throwable cause) {
        super("client disconnected", cause);
    }
}
