package com.wherelee.helpdesk.outbox;

import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String STREAM = "helpdesk:stream:index";

    private final OutboxRepository outbox;
    private final StringRedisTemplate redis;
    private final TenantRepository tenants;

    public OutboxPublisher(
            OutboxRepository outbox,
            StringRedisTemplate redis,
            TenantRepository tenants
    ) {
        this.outbox = outbox;
        this.redis = redis;
        this.tenants = tenants;
    }

    @Scheduled(fixedDelay = 2_000)
    public void publish() {
        Tenant platform = tenants.findBySlug("platform").orElse(null);
        if (platform == null) {
            return;
        }
        TenantContext.runWith(TenantScope.platform(platform.id()), () -> {
            outbox.resetStuck();
            for (OutboxRepository.OutboxEvent event : outbox.claimBatch(20)) {
                try {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("eventId", event.id().toString());
                    fields.put("tenantId", event.tenantId().toString());
                    fields.put("eventType", event.eventType());
                    fields.put("payload", event.payload());
                    redis.opsForStream().add(
                            StreamRecords.mapBacked(fields).withStreamKey(STREAM));
                    outbox.markPublished(event.id());
                    log.debug("helpdesk_outbox_published event={}", event.id());
                } catch (Exception ex) {
                    outbox.markFailed(event.id(), ex.getMessage());
                    log.warn("helpdesk_outbox_publish_failed event={} error={}",
                            event.id(), ex.getMessage());
                }
            }
        });
    }
}
