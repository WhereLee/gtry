package com.wherelee.helpdesk.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.attachment.AttachmentService;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.user.UserRepository;
import com.wherelee.helpdesk.wiki.JobStatus;
import com.wherelee.helpdesk.wiki.WikiRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformService {

    private static final Logger log = LoggerFactory.getLogger(PlatformService.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final WikiRepository wikiRepository;
    private final PlatformRepository platformRepository;
    private final TenantDeletionService deletionService;
    private final AttachmentService attachmentService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public PlatformService(
            TenantRepository tenants,
            UserRepository users,
            WikiRepository wikiRepository,
            PlatformRepository platformRepository,
            TenantDeletionService deletionService,
            AttachmentService attachmentService,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.tenants = tenants;
        this.users = users;
        this.wikiRepository = wikiRepository;
        this.platformRepository = platformRepository;
        this.deletionService = deletionService;
        this.attachmentService = attachmentService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public List<PlatformDtos.TenantSummary> listTenants() {
        requirePlatformAdmin();
        return tenants.listWithStats().stream().map(PlatformDtos.TenantSummary::of).toList();
    }

    public List<Map<String, Object>> auditLogs(
            String action, String targetType, String targetId, int limit
    ) {
        requirePlatformAdmin();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return platformRepository.searchAudit(action, targetType, targetId, safeLimit);
    }

    public PlatformDtos.TenantSummary createTenant(PlatformDtos.CreateTenantRequest request) {
        requirePlatformAdmin();
        if (tenants.findBySlug(request.slug()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_exists");
        }
        String plan = request.plan() == null || request.plan().isBlank()
                ? "free"
                : request.plan().trim().toLowerCase(Locale.ROOT);
        if (plan.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_plan");
        }
        long budget = request.monthlyTokenBudget() == null
                ? 200_000
                : Math.max(1_000, request.monthlyTokenBudget());
        String displayName = request.adminDisplayName() == null || request.adminDisplayName().isBlank()
                ? request.name() + " Admin"
                : request.adminDisplayName().trim();

        Tenant created;
        try {
            created = transactionTemplate.execute(status -> {
                Tenant tenant = tenants.create(request.slug(), request.name().trim(), plan, budget);
                users.create(
                        tenant.id(),
                        request.adminEmail().trim().toLowerCase(Locale.ROOT),
                        passwordEncoder.encode(request.adminPassword()),
                        displayName,
                        "TENANT_ADMIN"
                );
                wikiRepository.createSpace(
                        tenant.id(), "default", "默认知识库", "租户的默认知识空间");
                audit("tenant.create", tenant.id(), Map.of(
                        "slug", tenant.slug(),
                        "plan", tenant.plan(),
                        "adminEmail", request.adminEmail().trim().toLowerCase(Locale.ROOT)
                ));
                return tenant;
            });
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_exists");
        }
        if (created == null) {
            throw new IllegalStateException("tenant transaction returned no tenant");
        }
        return tenants.listWithStats().stream()
                .filter(stats -> stats.id().equals(created.id()))
                .map(PlatformDtos.TenantSummary::of)
                .findFirst()
                .orElseThrow();
    }

    public PlatformDtos.TenantSummary updateStatus(UUID tenantId, String status) {
        requirePlatformAdmin();
        Tenant tenant = findTenant(tenantId);
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "SUSPENDED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_tenant_status");
        }
        if ("DELETING".equals(tenant.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_deleting");
        }
        if ("platform".equals(tenant.slug()) && "SUSPENDED".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_suspend_platform");
        }
        tenants.updateStatus(tenantId, normalized);
        audit("tenant.status", tenantId, Map.of("status", normalized));
        return tenants.listWithStats().stream()
                .filter(stats -> stats.id().equals(tenantId))
                .map(PlatformDtos.TenantSummary::of)
                .findFirst()
                .orElseThrow();
    }

    public TenantExport exportTenant(UUID tenantId) {
        requirePlatformAdmin();
        Tenant tenant = findTenant(tenantId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", Instant.now().toString());
        data.put("tenant", Map.of(
                "id", tenant.id().toString(),
                "slug", tenant.slug(),
                "name", tenant.name(),
                "plan", tenant.plan(),
                "status", tenant.status(),
                "monthlyTokenBudget", tenant.monthlyTokenBudget()
        ));
        data.put("users", platformRepository.users(tenantId));
        data.put("tickets", platformRepository.tickets(tenantId));
        data.put("messages", platformRepository.messages(tenantId));
        data.put("wikiSpaces", platformRepository.wikiSpaces(tenantId));
        data.put("wikiPages", platformRepository.wikiPages(tenantId));
        data.put("wikiSections", platformRepository.wikiSections(tenantId));
        data.put("usageEvents", platformRepository.usageEvents(tenantId));
        data.put("auditLogs", platformRepository.auditLogs(tenantId));
        audit("tenant.export", tenantId, Map.of("slug", tenant.slug()));
        return new TenantExport(tenant.slug(), data);
    }

    public JobStatus.Snapshot deleteTenant(UUID tenantId, String confirm) {
        requirePlatformAdmin();
        Tenant tenant = findTenant(tenantId);
        if ("platform".equals(tenant.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_delete_platform");
        }
        if (confirm == null || !confirm.equals(tenant.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm_slug_required");
        }
        if (!tenants.softDelete(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_already_deleted");
        }
        audit("tenant.soft_delete", tenantId, Map.of("slug", tenant.slug(), "retentionDays", 30));
        JobStatus job = new JobStatus();
        job.start(1);
        job.done(Map.of("tenantId", tenantId.toString(), "slug", tenant.slug(), "recycleBin", true));
        return job.snapshot();
    }

    public PlatformDtos.TenantSummary restoreTenant(UUID tenantId) {
        requirePlatformAdmin();
        Tenant tenant = findTenant(tenantId);
        if (!"DELETED".equals(tenant.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_not_deleted");
        }
        if (!tenants.restore(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_restore_failed");
        }
        audit("tenant.restore", tenantId, Map.of("slug", tenant.slug()));
        return tenants.listWithStats().stream()
                .filter(stats -> stats.id().equals(tenantId))
                .map(PlatformDtos.TenantSummary::of)
                .findFirst()
                .orElseThrow();
    }

    public JobStatus.Snapshot purgeTenant(UUID tenantId, String confirm) {
        requirePlatformAdmin();
        Tenant tenant = findTenant(tenantId);
        if ("platform".equals(tenant.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_delete_platform");
        }
        if (!"DELETED".equals(tenant.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_not_deleted");
        }
        if (confirm == null || !confirm.equals(tenant.slug())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm_slug_required");
        }
        TenantScope actor = TenantContext.get();
        return deletionService.start(tenant, actor);
    }

    public int purgeExpired(int retentionDays) {
        Instant cutoff = Instant.now().minus(Math.max(1, retentionDays), ChronoUnit.DAYS);
        int purged = 0;
        for (Tenant tenant : tenants.findDeletedBefore(cutoff)) {
            try {
                platformRepository.insertAudit(
                        null, "tenant.purge", "tenant", tenant.id().toString(),
                        objectMapper.writeValueAsString(Map.of(
                                "slug", tenant.slug(), "retentionDays", retentionDays)));
                tenants.delete(tenant.id());
                attachmentService.deleteTenantFiles(tenant.id());
                purged++;
                log.info("helpdesk_tenant_purged tenant={} slug={}", tenant.id(), tenant.slug());
            } catch (Exception ex) {
                log.warn("helpdesk_tenant_purge_failed tenant={} slug={} error={}",
                        tenant.id(), tenant.slug(), ex.getMessage());
            }
        }
        return purged;
    }

    public JobStatus.Snapshot deletionStatus(UUID tenantId) {
        requirePlatformAdmin();
        return deletionService.status(tenantId);
    }

    private Tenant findTenant(UUID tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant_not_found"));
    }

    private void requirePlatformAdmin() {
        TenantScope scope = TenantContext.get();
        if (scope == null || !scope.platformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform_admin_required");
        }
    }

    private void audit(String action, UUID targetId, Map<String, ?> detail) {
        TenantScope scope = TenantContext.get();
        try {
            platformRepository.insertAudit(
                    scope == null ? null : scope.userId(),
                    action,
                    "tenant",
                    targetId.toString(),
                    objectMapper.writeValueAsString(detail)
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("platform audit write failed", ex);
        }
    }

    public record TenantExport(String slug, Map<String, Object> data) {
    }
}
