package com.wherelee.helpdesk.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ToolCallLogRepository {

    private final JdbcTemplate jdbc;

    public ToolCallLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId, UUID ticketId, String toolName,
            String argumentsJson, String resultJson, String status
    ) {
        jdbc.update("""
                INSERT INTO tool_call_log
                    (tenant_id, ticket_id, tool_name, arguments, result, status)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, tenantId, ticketId, toolName, argumentsJson, resultJson, status);
    }

    public List<Map<String, Object>> listByTicket(UUID ticketId) {
        return jdbc.queryForList("""
                SELECT id, tool_name, arguments::text AS arguments, result::text AS result,
                       status, created_at::text AS created_at
                FROM tool_call_log
                WHERE ticket_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """, ticketId);
    }
}
