package com.wherelee.helpdesk.ticket;

import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);

    private final TicketRepository tickets;
    private final TenantRepository tenants;
    private final Counter breachCounter;

    public SlaScheduler(
            TicketRepository tickets,
            TenantRepository tenants,
            MeterRegistry meterRegistry
    ) {
        this.tickets = tickets;
        this.tenants = tenants;
        this.breachCounter = meterRegistry.counter("helpdesk_sla_breaches_total");
    }

    @Scheduled(fixedDelay = 60_000)
    public void scan() {
        Tenant platform = tenants.findBySlug("platform").orElse(null);
        if (platform == null) {
            return;
        }
        TenantContext.runWith(TenantScope.platform(platform.id()), () -> {
            for (TicketRepository.Breach breach : tickets.markBreached()) {
                breachCounter.increment();
                log.warn("helpdesk_sla_breached tenant={} ticket={}", breach.tenantId(), breach.id());
            }
        });
    }
}
