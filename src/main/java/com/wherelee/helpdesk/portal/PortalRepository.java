package com.wherelee.helpdesk.portal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PortalRepository {

    private final JdbcTemplate jdbc;

    public PortalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChannelRef> resolveChannel(String keyHash) {
        return jdbc.query("""
                SELECT channel_id, tenant_id FROM resolve_channel(?::char(64))
                """, (rs, rowNum) -> new ChannelRef(
                rs.getObject("channel_id", UUID.class),
                rs.getObject("tenant_id", UUID.class)
        ), keyHash).stream().findFirst();
    }

    public Optional<TokenRef> resolveToken(String tokenHash) {
        return jdbc.query("""
                SELECT ticket_id, tenant_id, expires_at FROM resolve_ticket_token(?::char(64))
                """, (rs, rowNum) -> new TokenRef(
                rs.getObject("ticket_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()
        ), tokenHash).stream().findFirst();
    }

    public record ChannelRef(UUID channelId, UUID tenantId) {
    }

    public record TokenRef(UUID ticketId, UUID tenantId, Instant expiresAt) {
    }
}
