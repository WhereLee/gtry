package com.wherelee.helpdesk.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TenantPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgeScheduler.class);
    private static final int RETENTION_DAYS = 30;

    private final PlatformService platformService;

    public TenantPurgeScheduler(PlatformService platformService) {
        this.platformService = platformService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpiredTenants() {
        int purged = platformService.purgeExpired(RETENTION_DAYS);
        if (purged > 0) {
            log.info("helpdesk_tenant_purge_batch purged={} retentionDays={}", purged, RETENTION_DAYS);
        }
    }
}
