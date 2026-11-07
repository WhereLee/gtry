package com.wherelee.helpdesk.security;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final StringRedisTemplate redis;
    private final HelpdeskProperties.Security config;

    public LoginRateLimiter(StringRedisTemplate redis, HelpdeskProperties properties) {
        this.redis = redis;
        this.config = properties.getSecurity();
    }

    public void assertAllowed(String scope) {
        String window = String.valueOf(System.currentTimeMillis() / 60_000);
        String key = "helpdesk:login:rl:" + sha256(scope) + ":" + window;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofMinutes(2));
            }
            if (count != null && count > config.getLoginRateLimitPerMinute()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "login_rate_limited");
            }
        } catch (DataAccessException ex) {
            log.warn("helpdesk_login_rate_limit_unavailable error={}", ex.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
