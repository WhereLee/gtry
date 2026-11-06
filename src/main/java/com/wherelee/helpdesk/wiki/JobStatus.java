package com.wherelee.helpdesk.wiki;

import java.time.Instant;

public class JobStatus {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    private State state = State.IDLE;
    private int total;
    private int completed;
    private String message = "";
    private Instant startedAt;
    private Instant finishedAt;
    private Object result;

    public synchronized void start(int total) {
        this.state = State.RUNNING;
        this.total = total;
        this.completed = 0;
        this.message = "";
        this.startedAt = Instant.now();
        this.finishedAt = null;
        this.result = null;
    }

    public synchronized void progress(int completed) {
        this.completed = completed;
    }

    public synchronized void done(Object result) {
        this.state = State.DONE;
        this.result = result;
        this.finishedAt = Instant.now();
        this.completed = total;
    }

    public synchronized void fail(String message) {
        this.state = State.FAILED;
        this.message = message;
        this.finishedAt = Instant.now();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, total, completed, message, startedAt, finishedAt, result);
    }

    public record Snapshot(
            State state,
            int total,
            int completed,
            String message,
            Instant startedAt,
            Instant finishedAt,
            Object result
    ) {
    }
}
