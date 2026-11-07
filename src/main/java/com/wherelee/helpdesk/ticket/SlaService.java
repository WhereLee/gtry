package com.wherelee.helpdesk.ticket;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class SlaService {

    public Instant dueAt(String priority) {
        Duration duration = switch (priority == null ? "NORMAL" : priority.toUpperCase()) {
            case "URGENT" -> Duration.ofHours(1);
            case "HIGH" -> Duration.ofHours(4);
            case "LOW" -> Duration.ofHours(72);
            default -> Duration.ofHours(24);
        };
        return Instant.now().plus(duration);
    }
}
