package com.wherelee.helpdesk.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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
}
