package com.wherelee.helpdesk.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("attempts")
    );

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson
    ) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, tenantId, aggregateType, aggregateId, eventType, payloadJson);
    }

    public List<OutboxEvent> claimBatch(int limit) {
        return jdbc.query("""
                WITH claimed AS (
                    SELECT id FROM outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_event e
                SET status = 'PUBLISHING', next_attempt_at = now() + interval '30 seconds'
                FROM claimed
                WHERE e.id = claimed.id
                RETURNING e.id, e.tenant_id, e.aggregate_type, e.aggregate_id,
                          e.event_type, e.payload::text AS payload, e.attempts
                """, MAPPER, limit);
    }

    public void markPublished(UUID id) {
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'PUBLISHED', published_at = now(), last_error = NULL
                WHERE id = ?
                """, id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("""
                UPDATE outbox_event
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= 5 THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = now() + (LEAST(300, POWER(2, attempts + 1))::int * interval '1 second'),
                    last_error = ?
                WHERE id = ?
                """, error, id);
    }

    public void resetStuck() {
        jdbc.update("""
                UPDATE outbox_event
                SET status = 'PENDING'
                WHERE status = 'PUBLISHING' AND next_attempt_at < now()
                """);
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    public record OutboxEvent(
            UUID id,
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload,
            int attempts
    ) {
    }
}
