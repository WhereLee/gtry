package com.wherelee.helpdesk.quota;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final QuotaService quotaService;

    public UsageController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping("/summary")
    public QuotaService.UsageView summary() {
        TenantScope scope = TenantContext.get();
        return quotaService.summary(scope.tenantId());
    }
}
