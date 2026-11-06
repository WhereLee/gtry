package com.wherelee.helpdesk.portal;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
public class PortalRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PortalRateLimiter.class);

    private final StringRedisTemplate redis;
    private final HelpdeskProperties.PublicPortal config;

    public PortalRateLimiter(StringRedisTemplate redis, HelpdeskProperties properties) {
        this.redis = redis;
        this.config = properties.getPublicPortal();
    }

    public void assertAllowed(String scope) {
        String window = String.valueOf(System.currentTimeMillis() / 60_000);
        String key = "helpdesk:portal:rl:" + scope + ":" + window;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofMinutes(2));
            }
            if (count != null && count > config.getRateLimitPerMinute()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            }
        } catch (DataAccessException ex) {
            // Keep the customer portal usable when Redis is down; log for operators.
            log.warn("helpdesk_portal_rate_limit_unavailable scope={} error={}", scope, ex.getMessage());
        }
    }
}
