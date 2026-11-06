package com.wherelee.helpdesk.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WikiIndexStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(WikiIndexStreamConsumer.class);
    private static final String STREAM = "helpdesk:stream:index";
    private static final String DLQ = STREAM + ":dlq";
    private static final String GROUP = "helpdesk-index";
    private static final String CONSUMER = "helpdesk-" + UUID.randomUUID();
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redis;
    private final WikiIndexService indexService;
    private final ObjectMapper objectMapper;
    private volatile boolean groupReady;

    public WikiIndexStreamConsumer(
            StringRedisTemplate redis,
            WikiIndexService indexService,
            ObjectMapper objectMapper
    ) {
        this.redis = redis;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1_000)
    public void poll() {
        ensureGroup();
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(10),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed())
            );
            if (records != null) {
                for (MapRecord<String, Object, Object> record : records) {
                    process(record);
                }
            }
            recoverPending();
        } catch (Exception ex) {
            log.warn("helpdesk_index_stream_poll_failed error={}", ex.getMessage());
        }
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
            groupReady = true;
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                groupReady = true;
            }
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            String tenantId = stringValue(value.get("tenantId"));
            String payloadJson = stringValue(value.get("payload"));
            JsonNode payload = objectMapper.readTree(payloadJson);
            String pageId = payload.path("pageId").asText();
            if (tenantId == null || pageId.isBlank()) {
                throw new IllegalStateException("missing tenantId or pageId");
            }
            TenantContext.runWith(
                    TenantScope.tenant(UUID.fromString(tenantId)),
                    () -> indexService.indexPage(UUID.fromString(pageId))
            );
            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            redis.delete(attemptKey(record.getId()));
        } catch (Exception ex) {
            handleFailure(record, ex);
        }
    }

    private void handleFailure(MapRecord<String, Object, Object> record, Exception failure) {
        String key = attemptKey(record.getId());
        try {
            Long attempts = redis.opsForValue().increment(key);
            if (attempts != null && attempts == 1) {
                redis.expire(key, Duration.ofHours(1));
            }
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                Map<String, String> dlq = new LinkedHashMap<>();
                dlq.put("originalId", record.getId().getValue());
                dlq.put("tenantId", stringValue(record.getValue().get("tenantId")));
                dlq.put("eventType", stringValue(record.getValue().get("eventType")));
                dlq.put("payload", stringValue(record.getValue().get("payload")));
                dlq.put("error", failure.getMessage() == null ? "unknown" : failure.getMessage());
                dlq.put("attempts", String.valueOf(attempts));
                redis.opsForStream().add(StreamRecords.mapBacked(dlq).withStreamKey(DLQ));
                redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
                redis.delete(key);
                log.error("helpdesk_index_dlq id={} attempts={} error={}",
                        record.getId(), attempts, failure.getMessage());
            } else {
                log.warn("helpdesk_index_retry id={} attempts={} error={}",
                        record.getId(), attempts, failure.getMessage());
            }
        } catch (Exception ex) {
            log.error("helpdesk_index_failure_handler_failed id={} error={}",
                    record.getId(), ex.getMessage());
        }
    }

    private void recoverPending() {
        PendingMessages pending = redis.opsForStream().pending(
                STREAM, GROUP, Range.unbounded(), 10);
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().toMillis() < 60_000) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    STREAM, GROUP, CONSUMER, Duration.ofSeconds(60), message.getId());
            if (claimed != null) {
                for (MapRecord<String, Object, Object> record : claimed) {
                    process(record);
                }
            }
        }
    }

    private String attemptKey(org.springframework.data.redis.connection.stream.RecordId id) {
        return "helpdesk:index:attempts:" + id.getValue();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
