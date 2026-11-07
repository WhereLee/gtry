package com.wherelee.helpdesk.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.attachment.AttachmentService;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.wiki.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TenantDeletionService {

    private static final Logger log = LoggerFactory.getLogger(TenantDeletionService.class);

    private final TenantRepository tenants;
    private final PlatformRepository platformRepository;
    private final AttachmentService attachmentService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, JobStatus> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "helpdesk-tenant-delete");
        thread.setDaemon(true);
        return thread;
    });

    public TenantDeletionService(
            TenantRepository tenants,
            PlatformRepository platformRepository,
            AttachmentService attachmentService,
            ObjectMapper objectMapper
    ) {
        this.tenants = tenants;
        this.platformRepository = platformRepository;
        this.attachmentService = attachmentService;
        this.objectMapper = objectMapper;
    }

    public JobStatus.Snapshot start(Tenant tenant, TenantScope actor) {
        JobStatus existing = jobs.get(tenant.id());
        if (existing != null && existing.snapshot().state() == JobStatus.State.RUNNING) {
            return existing.snapshot();
        }
        JobStatus status = new JobStatus();
        status.start(1);
        jobs.put(tenant.id(), status);
        tenants.updateStatus(tenant.id(), "DELETING");
        executor.submit(() -> run(tenant, actor, status));
        return status.snapshot();
    }

    public JobStatus.Snapshot status(UUID tenantId) {
        JobStatus status = jobs.get(tenantId);
        return status == null ? new JobStatus().snapshot() : status.snapshot();
    }

    private void run(Tenant tenant, TenantScope actor, JobStatus status) {
        try {
            String detail = objectMapper.writeValueAsString(
                    Map.of("slug", tenant.slug(), "name", tenant.name()));
            TenantContext.runWith(TenantScope.platform(actor.tenantId()), () -> {
                platformRepository.insertAudit(
                        actor.userId(), "tenant.delete.start", "tenant", tenant.id().toString(), detail);
                tenants.delete(tenant.id());
                attachmentService.deleteTenantFiles(tenant.id());
                platformRepository.insertAudit(
                        actor.userId(), "tenant.delete.done", "tenant", tenant.id().toString(), detail);
            });
            status.done(Map.of("tenantId", tenant.id().toString(), "slug", tenant.slug()));
            log.info("helpdesk_tenant_deleted tenant={} slug={}", tenant.id(), tenant.slug());
        } catch (Exception ex) {
            tenants.updateStatus(tenant.id(), "DELETED");
            status.fail(ex.getMessage());
            log.error("helpdesk_tenant_delete_failed tenant={} slug={}", tenant.id(), tenant.slug(), ex);
        }
    }
}
