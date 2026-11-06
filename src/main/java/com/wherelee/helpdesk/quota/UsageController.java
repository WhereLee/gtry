package com.wherelee.helpdesk.quota;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/daily")
    public List<UsageRepository.DailyUsage> daily(@RequestParam(defaultValue = "14") int days) {
        TenantScope scope = TenantContext.get();
        return quotaService.daily(scope.tenantId(), days);
    }
}
