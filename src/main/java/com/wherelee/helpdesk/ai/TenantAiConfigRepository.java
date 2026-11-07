package com.wherelee.helpdesk.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantAiConfigRepository {

    private static final RowMapper<Config> MAPPER = (rs, rowNum) -> new Config(
            rs.getObject("tenant_id", UUID.class),
            rs.getString("model"),
            rs.getInt("top_k"),
            rs.getString("extra_instructions"),
            rs.getBoolean("enabled")
    );

    private final JdbcTemplate jdbc;

    public TenantAiConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Config> find(UUID tenantId) {
        return jdbc.query("""
                SELECT tenant_id, model, top_k, extra_instructions, enabled
                FROM tenant_ai_config WHERE tenant_id = ?
                """, MAPPER, tenantId).stream().findFirst();
    }

    public void upsert(
            UUID tenantId, String model, int topK, String extraInstructions, boolean enabled
    ) {
        jdbc.update("""
                INSERT INTO tenant_ai_config
                    (tenant_id, model, top_k, extra_instructions, enabled)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                SET model = EXCLUDED.model,
                    top_k = EXCLUDED.top_k,
                    extra_instructions = EXCLUDED.extra_instructions,
                    enabled = EXCLUDED.enabled,
                    updated_at = now()
                """, tenantId, model, topK, extraInstructions, enabled);
    }

    public record Config(
            UUID tenantId,
            String model,
            int topK,
            String extraInstructions,
            boolean enabled
    ) {
    }
}
