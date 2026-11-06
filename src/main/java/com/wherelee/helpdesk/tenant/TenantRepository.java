package com.wherelee.helpdesk.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Repository
public class TenantRepository {

    private static final RowMapper<Tenant> MAPPER = (rs, rowNum) -> new Tenant(
            rs.getObject("id", java.util.UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("plan"),
            rs.getString("status"),
            rs.getLong("monthly_token_budget")
    );

    private final JdbcTemplate jdbc;

    public TenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findBySlug(String slug) {
        return jdbc.query("""
                SELECT id, slug, name, plan, status, monthly_token_budget
                FROM tenant WHERE slug = ?
                """, MAPPER, slug).stream().findFirst();
    }

    public Optional<Tenant> findById(java.util.UUID id) {
        return jdbc.query("""
                SELECT id, slug, name, plan, status, monthly_token_budget
                FROM tenant WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public java.util.List<Tenant> findAll() {
        return jdbc.query("""
                SELECT id, slug, name, plan, status, monthly_token_budget
                FROM tenant ORDER BY created_at
                """, MAPPER);
    }

    public Tenant create(String slug, String name, String plan, long monthlyTokenBudget) {
        return jdbc.queryForObject("""
                INSERT INTO tenant (slug, name, plan, monthly_token_budget)
                VALUES (?, ?, ?, ?)
                RETURNING id, slug, name, plan, status, monthly_token_budget
                """, MAPPER, slug, name, plan, monthlyTokenBudget);
    }

    public boolean updateStatus(UUID id, String status) {
        return jdbc.update("""
                UPDATE tenant SET status = ?, updated_at = now() WHERE id = ?
                """, status, id) > 0;
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM tenant WHERE id = ?", id);
    }

    public List<TenantStats> listWithStats() {
        return jdbc.query("""
                SELECT t.id, t.slug, t.name, t.plan, t.status, t.monthly_token_budget,
                       t.created_at,
                       (SELECT count(*) FROM app_user u WHERE u.tenant_id = t.id) AS user_count,
                       (SELECT count(*) FROM ticket k WHERE k.tenant_id = t.id) AS ticket_count,
                       (SELECT COALESCE(sum(e.tokens), 0) FROM usage_event e
                        WHERE e.tenant_id = t.id
                          AND e.created_at >= date_trunc('month', now())) AS tokens_this_month,
                       (SELECT COALESCE(sum(e.cost_micros), 0) FROM usage_event e
                        WHERE e.tenant_id = t.id
                          AND e.created_at >= date_trunc('month', now())) AS cost_this_month
                FROM tenant t
                ORDER BY t.created_at
                """, (rs, rowNum) -> new TenantStats(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("plan"),
                rs.getString("status"),
                rs.getLong("monthly_token_budget"),
                rs.getLong("user_count"),
                rs.getLong("ticket_count"),
                rs.getLong("tokens_this_month"),
                rs.getLong("cost_this_month"),
                rs.getTimestamp("created_at").toInstant()
        ));
    }

    public record TenantStats(
            UUID id, String slug, String name, String plan, String status,
            long monthlyTokenBudget, long userCount, long ticketCount,
            long tokensThisMonth, long costMicrosThisMonth, Instant createdAt
    ) {
    }
}
