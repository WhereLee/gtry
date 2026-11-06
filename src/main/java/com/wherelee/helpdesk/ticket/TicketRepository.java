package com.wherelee.helpdesk.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TicketRepository {

    private static final RowMapper<Ticket> MAPPER = (rs, rowNum) -> new Ticket(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("channel_id", UUID.class),
            rs.getString("external_ref"),
            rs.getString("subject"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getObject("assignee_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final RowMapper<TicketMessage> MESSAGE_MAPPER = (rs, rowNum) -> new TicketMessage(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("ticket_id", UUID.class),
            rs.getString("author_type"),
            rs.getObject("author_id", UUID.class),
            rs.getString("content"),
            rs.getString("citations"),
            rs.getInt("tokens"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Ticket create(UUID tenantId, String subject, String description, String priority, UUID assigneeId) {
        return jdbc.queryForObject("""
                INSERT INTO ticket (tenant_id, subject, description, priority, assignee_id)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, tenant_id, channel_id, external_ref, subject, description,
                          status, priority, assignee_id, created_at, updated_at
                """, MAPPER, tenantId, subject, description, priority, assigneeId);
    }

    public Optional<Ticket> findById(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, channel_id, external_ref, subject, description,
                       status, priority, assignee_id, created_at, updated_at
                FROM ticket WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Ticket> list(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT id, tenant_id, channel_id, external_ref, subject, description,
                           status, priority, assignee_id, created_at, updated_at
                    FROM ticket ORDER BY created_at DESC LIMIT ?
                    """, MAPPER, limit);
        }
        return jdbc.query("""
                SELECT id, tenant_id, channel_id, external_ref, subject, description,
                       status, priority, assignee_id, created_at, updated_at
                FROM ticket WHERE status = ? ORDER BY created_at DESC LIMIT ?
                """, MAPPER, status, limit);
    }

    public boolean updateStatus(UUID id, String status) {
        return jdbc.update("""
                UPDATE ticket SET status = ?, updated_at = now() WHERE id = ?
                """, status, id) > 0;
    }

    public TicketMessage addMessage(
            UUID tenantId,
            UUID ticketId,
            String authorType,
            UUID authorId,
            String content,
            String citationsJson,
            int tokens
    ) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_message
                    (tenant_id, ticket_id, author_type, author_id, content, citations, tokens)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, tenant_id, ticket_id, author_type, author_id,
                          content, citations::text AS citations, tokens, created_at
                """, MESSAGE_MAPPER, tenantId, ticketId, authorType, authorId, content, citationsJson, tokens);
    }

    public List<TicketMessage> listMessages(UUID ticketId) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, author_type, author_id,
                       content, citations::text AS citations, tokens, created_at
                FROM ticket_message WHERE ticket_id = ? ORDER BY created_at
                """, MESSAGE_MAPPER, ticketId);
    }

    public int countByStatus(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
    }

    public void touch(UUID ticketId) {
        jdbc.update("UPDATE ticket SET updated_at = now() WHERE id = ?", ticketId);
    }

    public int delete(UUID ticketId) {
        return jdbc.update("DELETE FROM ticket WHERE id = ?", ticketId);
    }

    public Instant createdAt(UUID ticketId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT created_at FROM ticket WHERE id = ?", Timestamp.class, ticketId);
        return ts == null ? null : ts.toInstant();
    }
}
