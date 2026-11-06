package com.wherelee.helpdesk.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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
}
