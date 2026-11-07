package com.wherelee.helpdesk.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;

    public TokenRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set("helpdesk:revoked:" + jti, "1", ttl);
        } catch (DataAccessException ex) {
            log.warn("helpdesk_token_revoke_unavailable jti={} error={}", jti, ex.getMessage());
        }
    }

    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey("helpdesk:revoked:" + jti));
        } catch (DataAccessException ex) {
            log.warn("helpdesk_token_check_unavailable error={}", ex.getMessage());
            return false;
        }
    }
}
