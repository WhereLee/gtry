package com.wherelee.helpdesk.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class AiFeedbackRepository {

    private final JdbcTemplate jdbc;

    public AiFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId, UUID ticketId, UUID messageId, UUID actorId,
            String action, String originalContent, String editedContent
    ) {
        jdbc.update("""
                INSERT INTO ai_feedback
                    (tenant_id, ticket_id, message_id, actor_id, action, original_content, edited_content)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, ticketId, messageId, actorId, action, originalContent, editedContent);
    }
}
