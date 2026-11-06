package com.wherelee.helpdesk.quota;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
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
                WHERE tenant_id = ?
                  AND created_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Shanghai')
                      AT TIME ZONE 'Asia/Shanghai'
                """, Long.class, tenantId);
        return sum == null ? 0 : sum;
    }

    public long costMicrosThisMonth(UUID tenantId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cost_micros), 0) FROM usage_event
                WHERE tenant_id = ?
                  AND created_at >= date_trunc('month', now() AT TIME ZONE 'Asia/Shanghai')
                      AT TIME ZONE 'Asia/Shanghai'
                """, Long.class, tenantId);
        return sum == null ? 0 : sum;
    }

    public List<DailyUsage> daily(UUID tenantId, int days) {
        return jdbc.query("""
                SELECT to_char((created_at AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD') AS day,
                       COALESCE(SUM(tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micros), 0) AS cost_micros
                FROM usage_event
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                GROUP BY (created_at AT TIME ZONE 'Asia/Shanghai')::date
                ORDER BY (created_at AT TIME ZONE 'Asia/Shanghai')::date
                """, (rs, rowNum) -> new DailyUsage(
                rs.getString("day"), rs.getLong("tokens"), rs.getLong("cost_micros")
        ), tenantId, days);
    }

    public record DailyUsage(String date, long tokens, long costMicros) {
    }
}
