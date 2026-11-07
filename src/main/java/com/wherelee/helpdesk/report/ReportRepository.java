package com.wherelee.helpdesk.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> ticketSummary(UUID tenantId, int days) {
        return jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'OPEN') AS open,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'CLOSED') AS closed,
                       count(*) FILTER (WHERE sla_breached) AS sla_breached,
                       avg(extract(epoch FROM (first_response_at - created_at)))
                           FILTER (WHERE first_response_at IS NOT NULL) AS first_response_avg_seconds,
                       avg(extract(epoch FROM (resolved_at - created_at)))
                           FILTER (WHERE resolved_at IS NOT NULL) AS resolution_avg_seconds,
                       avg(csat_rating) FILTER (WHERE csat_rating IS NOT NULL) AS csat_avg,
                       count(csat_rating) AS csat_count
                FROM ticket
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                """, tenantId, days);
    }

    public Map<String, Object> aiFeedback(UUID tenantId, int days) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE action = 'ADOPT') AS adopt,
                       count(*) FILTER (WHERE action = 'EDIT') AS edit,
                       count(*) FILTER (WHERE action = 'REJECT') AS reject
                FROM ai_feedback
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                """, tenantId, days));
        long adopt = ((Number) result.getOrDefault("adopt", 0)).longValue();
        long edit = ((Number) result.getOrDefault("edit", 0)).longValue();
        long reject = ((Number) result.getOrDefault("reject", 0)).longValue();
        long total = adopt + edit + reject;
        result.put("total", total);
        result.put("adoptionRate", total == 0 ? 0.0 : Math.round(adopt * 10000.0 / total) / 100.0);
        return result;
    }

    public Object byCategory(UUID tenantId, int days) {
        return jdbc.queryForList("""
                SELECT category, count(*) AS count
                FROM ticket
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                GROUP BY category ORDER BY count DESC
                """, tenantId, days);
    }

    public Object byPriority(UUID tenantId, int days) {
        return jdbc.queryForList("""
                SELECT priority, count(*) AS count
                FROM ticket
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                GROUP BY priority ORDER BY count DESC
                """, tenantId, days);
    }
}
