package com.wherelee.helpdesk.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class UserRepository {

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getObject("id", java.util.UUID.class),
            rs.getObject("tenant_id", java.util.UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getString("status")
    );

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("""
                SELECT id, tenant_id, email, password_hash, display_name, role, status
                FROM app_user WHERE email = ?
                """, MAPPER, email).stream().findFirst();
    }

    public Optional<AppUser> findById(java.util.UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, email, password_hash, display_name, role, status
                FROM app_user WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public AppUser create(
            UUID tenantId, String email, String passwordHash, String displayName, String role
    ) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, tenant_id, email, password_hash, display_name, role, status
                """, MAPPER, tenantId, email, passwordHash, displayName, role);
    }

    public List<AppUser> listByTenant(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, email, password_hash, display_name, role, status
                FROM app_user WHERE tenant_id = ? ORDER BY created_at
                """, MAPPER, tenantId);
    }

    public List<AppUser> listAssignable(UUID tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, email, password_hash, display_name, role, status
                FROM app_user
                WHERE tenant_id = ?
                  AND role IN ('AGENT', 'TENANT_ADMIN') AND status = 'ACTIVE'
                ORDER BY display_name
                """, MAPPER, tenantId);
    }

    public boolean updateRoleStatus(UUID id, String role, String status) {
        return jdbc.update("""
                UPDATE app_user SET role = ?, status = ? WHERE id = ?
                """, role, status, id) > 0;
    }

    public long countActiveTenantAdmins(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM app_user
                WHERE tenant_id = ? AND role = 'TENANT_ADMIN' AND status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0 : count;
    }
}
