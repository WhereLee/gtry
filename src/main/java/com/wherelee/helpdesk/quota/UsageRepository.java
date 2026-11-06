package com.wherelee.helpdesk.quota;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class UsageRepository {

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID tenantId, String kind, int tokens, long costMicros) {
        jdbc.update("""
                INSERT INTO usage_event (tenant_id, kind, tokens, cost_micros)
                VALUES (?, ?, ?, ?)
                """, tenantId, kind, tokens, costMicros);
    }

    public long tokensThisMonth(UUID tenantId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(tokens), 0) FROM usage_event
                WHERE tenant_id = ? AND created_at >= date_trunc('month', now())
                """, Long.class, tenantId);
        return sum == null ? 0 : sum;
    }

    public long costMicrosThisMonth(UUID tenantId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cost_micros), 0) FROM usage_event
                WHERE tenant_id = ? AND created_at >= date_trunc('month', now())
                """, Long.class, tenantId);
        return sum == null ? 0 : sum;
    }
}
