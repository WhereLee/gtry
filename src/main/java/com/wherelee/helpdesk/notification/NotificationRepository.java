package com.wherelee.helpdesk.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {

    private static final RowMapper<Notification> MAPPER = (rs, rowNum) -> new Notification(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("target_type"),
            rs.getString("target_id"),
            rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId, UUID userId, String type, String title, String body,
            String targetType, String targetId
    ) {
        jdbc.update("""
                INSERT INTO notification
                    (tenant_id, user_id, type, title, body, target_type, target_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, userId, type, title, body, targetType, targetId);
    }

    public List<Notification> list(UUID userId, boolean unreadOnly, int limit) {
        if (unreadOnly) {
            return jdbc.query("""
                    SELECT id, tenant_id, user_id, type, title, body, target_type, target_id, read_at, created_at
                    FROM notification
                    WHERE user_id = ? AND read_at IS NULL
                    ORDER BY created_at DESC LIMIT ?
                    """, MAPPER, userId, limit);
        }
        return jdbc.query("""
                SELECT id, tenant_id, user_id, type, title, body, target_type, target_id, read_at, created_at
                FROM notification
                WHERE user_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, MAPPER, userId, limit);
    }

    public long unreadCount(UUID userId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM notification WHERE user_id = ? AND read_at IS NULL
                """, Long.class, userId);
        return count == null ? 0 : count;
    }

    public int markRead(UUID userId, UUID id) {
        return jdbc.update("""
                UPDATE notification SET read_at = now()
                WHERE id = ? AND user_id = ? AND read_at IS NULL
                """, id, userId);
    }

    public int markAllRead(UUID userId) {
        return jdbc.update("""
                UPDATE notification SET read_at = now()
                WHERE user_id = ? AND read_at IS NULL
                """, userId);
    }
}
