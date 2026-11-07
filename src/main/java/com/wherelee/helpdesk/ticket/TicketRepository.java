package com.wherelee.helpdesk.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
            rs.getString("category"),
            rs.getString("triage_source"),
            rs.getString("triage_reason"),
            rs.getString("customer_name"),
            rs.getString("customer_email"),
            rs.getObject("csat_rating", Integer.class),
            rs.getString("csat_comment"),
            rs.getTimestamp("csat_at") == null ? null : rs.getTimestamp("csat_at").toInstant(),
            rs.getTimestamp("first_response_at") == null ? null : rs.getTimestamp("first_response_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getTimestamp("sla_due_at") == null ? null : rs.getTimestamp("sla_due_at").toInstant(),
            rs.getBoolean("sla_breached"),
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
            rs.getString("visibility"),
            rs.getString("citations"),
            rs.getInt("tokens"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Ticket create(
            UUID tenantId, String subject, String description, String priority,
            String category, String triageSource, String triageReason, UUID assigneeId,
            String customerName, String customerEmail, Instant slaDueAt
    ) {
        return jdbc.queryForObject("""
                INSERT INTO ticket
                    (tenant_id, subject, description, priority, category,
                     triage_source, triage_reason, assignee_id, customer_name, customer_email, sla_due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, channel_id, external_ref, subject, description,
                          status, priority, category, triage_source, triage_reason,
                          customer_name, customer_email, csat_rating, csat_comment, csat_at,
                          first_response_at, resolved_at, sla_due_at, sla_breached,
                          assignee_id, created_at, updated_at
                """, MAPPER, tenantId, subject, description, priority, category,
                triageSource, triageReason, assigneeId, customerName, customerEmail, Timestamp.from(slaDueAt));
    }

    public Optional<Ticket> findById(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, channel_id, external_ref, subject, description,
                       status, priority, category, triage_source, triage_reason,
                       customer_name, customer_email, csat_rating, csat_comment, csat_at,
                       first_response_at, resolved_at, sla_due_at, sla_breached,
                       assignee_id, created_at, updated_at
                FROM ticket WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Ticket> list(String status, int limit) {
        return list(status, null, limit, 0);
    }

    public List<Ticket> list(String status, String query, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, channel_id, external_ref, subject, description,
                       status, priority, category, triage_source, triage_reason,
                       customer_name, customer_email, csat_rating, csat_comment, csat_at,
                       first_response_at, resolved_at, sla_due_at, sla_breached,
                       assignee_id, created_at, updated_at
                FROM ticket
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, query);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String status, String query) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ticket WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, status, query);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> args, String status, String query
    ) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            sql.append(" AND (subject ILIKE ? OR customer_name ILIKE ? OR customer_email ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }

    public boolean updateStatus(UUID id, String status) {
        return jdbc.update("""
                UPDATE ticket SET status = ?, updated_at = now() WHERE id = ?
                """, status, id) > 0;
    }

    public void markFirstResponse(UUID id) {
        jdbc.update("""
                UPDATE ticket
                SET first_response_at = COALESCE(first_response_at, now()), updated_at = now()
                WHERE id = ?
                """, id);
    }

    public void markResolved(UUID id) {
        jdbc.update("""
                UPDATE ticket SET resolved_at = now(), updated_at = now() WHERE id = ?
                """, id);
    }

    public void clearResolved(UUID id) {
        jdbc.update("""
                UPDATE ticket SET resolved_at = NULL, updated_at = now() WHERE id = ?
                """, id);
    }

    public List<Breach> markBreached() {
        return jdbc.query("""
                UPDATE ticket
                SET sla_breached = true, updated_at = now()
                WHERE status IN ('OPEN', 'PENDING')
                  AND sla_due_at IS NOT NULL
                  AND sla_due_at < now()
                  AND sla_breached = false
                RETURNING id, tenant_id
                """, (rs, rowNum) -> new Breach(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class)));
    }

    public List<Ticket> listBreached(int limit) {
        return jdbc.query("""
                SELECT id, tenant_id, channel_id, external_ref, subject, description,
                       status, priority, category, triage_source, triage_reason,
                       customer_name, customer_email, csat_rating, csat_comment, csat_at,
                       first_response_at, resolved_at, sla_due_at, sla_breached,
                       assignee_id, created_at, updated_at
                FROM ticket
                WHERE sla_breached = true AND status IN ('OPEN', 'PENDING')
                ORDER BY sla_due_at
                LIMIT ?
                """, MAPPER, limit);
    }

    public boolean updateTriage(
            UUID id, String category, String priority, UUID assigneeId,
            String source, String reason
    ) {
        return jdbc.update("""
                UPDATE ticket
                SET category = ?, priority = ?, assignee_id = ?,
                    triage_source = ?, triage_reason = ?, updated_at = now()
                WHERE id = ?
                """, category, priority, assigneeId, source, reason, id) > 0;
    }

    public boolean setCustomerToken(UUID ticketId, String tokenHash, Instant expiresAt) {
        return jdbc.update("""
                UPDATE ticket
                SET customer_token_hash = ?, customer_token_expires_at = ?, updated_at = now()
                WHERE id = ?
                """, tokenHash, Timestamp.from(expiresAt), ticketId) > 0;
    }

    public boolean updateCsat(UUID ticketId, int rating, String comment) {
        return jdbc.update("""
                UPDATE ticket
                SET csat_rating = ?, csat_comment = ?, csat_at = now(), updated_at = now()
                WHERE id = ?
                """, rating, comment, ticketId) > 0;
    }

    public Optional<UUID> findLeastLoadedAgent(UUID tenantId) {
        return jdbc.query("""
                SELECT u.id
                FROM app_user u
                WHERE u.tenant_id = ?
                  AND u.role IN ('AGENT', 'TENANT_ADMIN') AND u.status = 'ACTIVE'
                ORDER BY CASE WHEN u.role = 'AGENT' THEN 0 ELSE 1 END,
                (
                    SELECT count(*) FROM ticket t
                    WHERE t.assignee_id = u.id AND t.status IN ('OPEN', 'PENDING')
                ) ASC, u.id
                LIMIT 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId).stream().findFirst();
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
        return addMessage(tenantId, ticketId, authorType, authorId, content, citationsJson, tokens, "PUBLIC");
    }

    public TicketMessage addMessage(
            UUID tenantId,
            UUID ticketId,
            String authorType,
            UUID authorId,
            String content,
            String citationsJson,
            int tokens,
            String visibility
    ) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_message
                    (tenant_id, ticket_id, author_type, author_id, content, visibility, citations, tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id, tenant_id, ticket_id, author_type, author_id,
                          content, visibility, citations::text AS citations, tokens, created_at
                """, MESSAGE_MAPPER, tenantId, ticketId, authorType, authorId, content,
                visibility, citationsJson, tokens);
    }

    public List<TicketMessage> listMessages(UUID ticketId) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, author_type, author_id,
                       content, visibility, citations::text AS citations, tokens, created_at
                FROM ticket_message WHERE ticket_id = ? ORDER BY created_at
                """, MESSAGE_MAPPER, ticketId);
    }

    public List<TicketMessage> listPublicMessages(UUID ticketId) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, author_type, author_id,
                       content, visibility, citations::text AS citations, tokens, created_at
                FROM ticket_message
                WHERE ticket_id = ? AND visibility = 'PUBLIC'
                ORDER BY created_at
                """, MESSAGE_MAPPER, ticketId);
    }

    public Optional<TicketMessage> findMessage(UUID ticketId, UUID messageId) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, author_type, author_id,
                       content, visibility, citations::text AS citations, tokens, created_at
                FROM ticket_message
                WHERE ticket_id = ? AND id = ?
                """, MESSAGE_MAPPER, ticketId, messageId).stream().findFirst();
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

    public record Breach(UUID id, UUID tenantId) {
    }
}
