package com.wherelee.helpdesk.quota;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

@Service
public class QuotaService {

    /** 2 micro-units per token is a stand-in for the real per-model price. */
    private static final long COST_MICROS_PER_TOKEN = 2;

    private static final DefaultRedisScript<Long> FIXED_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final TenantRepository tenants;
    private final UsageRepository usage;
    private final HelpdeskProperties.Quota config;

    public QuotaService(
            StringRedisTemplate redis,
            TenantRepository tenants,
            UsageRepository usage,
            HelpdeskProperties properties
    ) {
        this.redis = redis;
        this.tenants = tenants;
        this.usage = usage;
        this.config = properties.getQuota();
    }

    public void assertAllowed(UUID tenantId) {
        String window = String.valueOf(System.currentTimeMillis() / 60_000);
        Long count = redis.execute(
                FIXED_WINDOW,
                List.of("helpdesk:rl:" + tenantId + ":" + window),
                "70"
        );
        if (count != null && count > config.getRateLimitPerMinute()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_not_found"));
        long used = usage.tokensThisMonth(tenantId);
        if (used >= tenant.monthlyTokenBudget()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "monthly_budget_exceeded");
        }
    }

    public void recordUsage(UUID tenantId, String kind, int tokens) {
        usage.record(tenantId, kind, tokens, tokens * COST_MICROS_PER_TOKEN);
    }

    public UsageView summary(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_not_found"));
        long tokens = usage.tokensThisMonth(tenantId);
        long cost = usage.costMicrosThisMonth(tenantId);
        return new UsageView(
                tenant.slug(),
                tokens,
                tenant.monthlyTokenBudget(),
                cost,
                config.getRateLimitPerMinute()
        );
    }

    public List<UsageRepository.DailyUsage> daily(UUID tenantId, int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        Map<String, UsageRepository.DailyUsage> byDate = new HashMap<>();
        for (UsageRepository.DailyUsage row : usage.daily(tenantId, safeDays)) {
            byDate.put(row.date(), row);
        }
        List<UsageRepository.DailyUsage> result = new ArrayList<>(safeDays);
        LocalDate today = LocalDate.now();
        for (int i = safeDays - 1; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            result.add(byDate.getOrDefault(date, new UsageRepository.DailyUsage(date, 0, 0)));
        }
        return result;
    }

    public record UsageView(
            String tenantSlug,
            long tokensThisMonth,
            long monthlyTokenBudget,
            long costMicrosThisMonth,
            int rateLimitPerMinute
    ) {
    }
}
