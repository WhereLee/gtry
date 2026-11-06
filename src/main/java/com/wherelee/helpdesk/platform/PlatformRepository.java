package com.wherelee.helpdesk.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PlatformRepository {

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAudit(
            UUID actorId, String action, String targetType, String targetId, String detailJson
    ) {
        jdbc.update("""
                INSERT INTO platform_audit_log (actor_id, action, target_type, target_id, detail)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actorId, action, targetType, targetId, detailJson);
    }

    public List<Map<String, Object>> users(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, email, display_name AS "displayName", role, status,
                       created_at::text AS "createdAt"
                FROM app_user WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> tickets(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, subject, description, status, priority, category,
                       triage_source AS "triageSource", triage_reason AS "triageReason",
                       assignee_id AS "assigneeId",
                       created_at::text AS "createdAt", updated_at::text AS "updatedAt"
                FROM ticket WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> messages(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, ticket_id AS "ticketId", author_type AS "authorType",
                       author_id AS "authorId", content,
                       citations::text AS citations, tokens, created_at::text AS "createdAt"
                FROM ticket_message WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> wikiSpaces(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, slug, name, description, created_at::text AS "createdAt"
                FROM wiki_space WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> wikiPages(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, space_id AS "spaceId", slug, title, visibility, status,
                       owner_id AS "ownerId",
                       last_reviewed_at::text AS "lastReviewedAt",
                       created_at::text AS "createdAt", updated_at::text AS "updatedAt"
                FROM wiki_page WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> wikiSections(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, page_id AS "pageId", seq, heading_path AS "headingPath", content,
                       created_at::text AS "createdAt", updated_at::text AS "updatedAt"
                FROM wiki_section WHERE tenant_id = ? ORDER BY page_id, seq
                """, tenantId);
    }

    public List<Map<String, Object>> usageEvents(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT kind, tokens, cost_micros AS "costMicros", created_at::text AS "createdAt"
                FROM usage_event WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }

    public List<Map<String, Object>> auditLogs(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT actor_id AS "actorId", action, target_type AS "targetType",
                       target_id AS "targetId", detail::text AS detail,
                       created_at::text AS "createdAt"
                FROM audit_log WHERE tenant_id = ? ORDER BY created_at
                """, tenantId);
    }
}
