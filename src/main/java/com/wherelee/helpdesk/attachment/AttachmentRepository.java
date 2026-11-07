package com.wherelee.helpdesk.attachment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AttachmentRepository {

    private static final RowMapper<Attachment> MAPPER = (rs, rowNum) -> new Attachment(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("ticket_id", UUID.class),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("storage_key"),
            rs.getString("visibility"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Attachment insert(
            UUID tenantId, UUID ticketId, String filename, String contentType,
            long sizeBytes, String storageKey, String visibility
    ) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_attachment
                    (tenant_id, ticket_id, filename, content_type, size_bytes, storage_key, visibility)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, ticket_id, filename, content_type,
                          size_bytes, storage_key, visibility, created_at
                """, MAPPER, tenantId, ticketId, filename, contentType, sizeBytes, storageKey, visibility);
    }

    public List<Attachment> listByTicket(UUID ticketId, boolean publicOnly) {
        if (publicOnly) {
            return jdbc.query("""
                    SELECT id, tenant_id, ticket_id, filename, content_type,
                           size_bytes, storage_key, visibility, created_at
                    FROM ticket_attachment
                    WHERE ticket_id = ? AND visibility = 'PUBLIC'
                    ORDER BY created_at
                    """, MAPPER, ticketId);
        }
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, filename, content_type,
                       size_bytes, storage_key, visibility, created_at
                FROM ticket_attachment
                WHERE ticket_id = ?
                ORDER BY created_at
                """, MAPPER, ticketId);
    }

    public Optional<Attachment> findById(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, ticket_id, filename, content_type,
                       size_bytes, storage_key, visibility, created_at
                FROM ticket_attachment WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public record Attachment(
            UUID id,
            UUID tenantId,
            UUID ticketId,
            String filename,
            String contentType,
            long sizeBytes,
            String storageKey,
            String visibility,
            Instant createdAt
    ) {
    }
}
