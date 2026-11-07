package com.wherelee.helpdesk.ticket;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlaServiceTest {

    private final SlaService sla = new SlaService();

    @Test
    void usesPrioritySpecificTargets() {
        Instant now = Instant.now();
        assertThat(Duration.between(now, sla.dueAt("URGENT")).toMinutes()).isBetween(59L, 61L);
        assertThat(Duration.between(now, sla.dueAt("HIGH")).toMinutes()).isBetween(239L, 241L);
        assertThat(Duration.between(now, sla.dueAt("NORMAL")).toHours()).isBetween(23L, 24L);
        assertThat(Duration.between(now, sla.dueAt("LOW")).toHours()).isBetween(71L, 72L);
    }
}
