package com.wherelee.helpdesk.security;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.tenant.TenantScope;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(HelpdeskProperties properties) {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("helpdesk.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(properties.getJwt().getTtlMinutes());
    }

    public String issue(UUID tenantId, UUID userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public TenantScope parse(String token) {
        return parseDetails(token).scope();
    }

    public ParsedToken parseDetails(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String role = claims.get("role", String.class);
        boolean platformAdmin = "PLATFORM_ADMIN".equals(role);
        TenantScope scope = new TenantScope(tenantId, userId, email, role, platformAdmin);
        String jti = claims.getId() == null ? "legacy" : claims.getId();
        Instant expiresAt = claims.getExpiration().toInstant();
        return new ParsedToken(scope, jti, expiresAt);
    }

    public record ParsedToken(TenantScope scope, String jti, Instant expiresAt) {
    }
}
