package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class TenantAiConfigService {

    private final TenantAiConfigRepository repository;
    private final HelpdeskProperties.Llm defaults;

    public TenantAiConfigService(
            TenantAiConfigRepository repository,
            HelpdeskProperties properties
    ) {
        this.repository = repository;
        this.defaults = properties.getLlm();
    }

    public TenantAiConfigRepository.Config resolve(UUID tenantId) {
        return repository.find(tenantId).orElseGet(() -> new TenantAiConfigRepository.Config(
                tenantId, defaults.getModel(), 5, "", true));
    }

    public TenantAiConfigRepository.Config current() {
        TenantScope scope = requireScope();
        return resolve(scope.tenantId());
    }

    public TenantAiConfigRepository.Config resolveCurrentOrDefault() {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            return new TenantAiConfigRepository.Config(null, defaults.getModel(), 5, "", true);
        }
        return resolve(scope.tenantId());
    }

    public TenantAiConfigRepository.Config update(AiConfigDtos.UpdateAiConfigRequest request) {
        TenantScope scope = requireTenantAdmin();
        String model = request.model().trim();
        String extra = request.extraInstructions() == null ? "" : request.extraInstructions().trim();
        repository.upsert(scope.tenantId(), model, request.topK(), extra, request.enabled());
        return resolve(scope.tenantId());
    }

    private TenantScope requireScope() {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        return scope;
    }

    private TenantScope requireTenantAdmin() {
        TenantScope scope = requireScope();
        if (!scope.platformAdmin() && !"TENANT_ADMIN".equals(scope.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_admin_required");
        }
        return scope;
    }
}
