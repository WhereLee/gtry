package com.wherelee.helpdesk.platform;

import com.wherelee.helpdesk.wiki.JobStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final PlatformService platformService;

    public PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    @GetMapping("/tenants")
    public List<PlatformDtos.TenantSummary> tenants() {
        return platformService.listTenants();
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformDtos.TenantSummary createTenant(
            @Valid @RequestBody PlatformDtos.CreateTenantRequest request
    ) {
        return platformService.createTenant(request);
    }

    @PatchMapping("/tenants/{id}/status")
    public PlatformDtos.TenantSummary updateStatus(
            @PathVariable UUID id,
            @RequestParam String status
    ) {
        return platformService.updateStatus(id, status);
    }

    @GetMapping("/tenants/{id}/export")
    public ResponseEntity<Map<String, Object>> export(@PathVariable UUID id) {
        PlatformService.TenantExport export = platformService.exportTenant(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tenant-" + export.slug() + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(export.data());
    }

    @DeleteMapping("/tenants/{id}")
    public JobStatus.Snapshot delete(
            @PathVariable UUID id,
            @RequestParam String confirm
    ) {
        return platformService.deleteTenant(id, confirm);
    }

    @PostMapping("/tenants/{id}/restore")
    public PlatformDtos.TenantSummary restore(@PathVariable UUID id) {
        return platformService.restoreTenant(id);
    }

    @DeleteMapping("/tenants/{id}/purge")
    public JobStatus.Snapshot purge(
            @PathVariable UUID id,
            @RequestParam String confirm
    ) {
        return platformService.purgeTenant(id, confirm);
    }

    @GetMapping("/tenants/{id}/delete-status")
    public JobStatus.Snapshot deleteStatus(@PathVariable UUID id) {
        return platformService.deletionStatus(id);
    }

    @GetMapping("/usage")
    public List<PlatformDtos.TenantSummary> usage() {
        return platformService.listTenants();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return platformService.auditLogs(action, targetType, targetId, limit);
    }
}
