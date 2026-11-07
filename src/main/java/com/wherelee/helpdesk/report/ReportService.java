package com.wherelee.helpdesk.report;

import com.wherelee.helpdesk.quota.QuotaService;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReportService {

    private final ReportRepository repository;
    private final QuotaService quotaService;

    public ReportService(ReportRepository repository, QuotaService quotaService) {
        this.repository = repository;
        this.quotaService = quotaService;
    }

    public Map<String, Object> tenantReport(int days) {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        int safeDays = Math.max(1, Math.min(days, 90));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("days", safeDays);
        report.put("tickets", repository.ticketSummary(scope.tenantId(), safeDays));
        report.put("aiFeedback", repository.aiFeedback(scope.tenantId(), safeDays));
        report.put("byCategory", repository.byCategory(scope.tenantId(), safeDays));
        report.put("byPriority", repository.byPriority(scope.tenantId(), safeDays));
        report.put("usage", quotaService.summary(scope.tenantId()));
        return report;
    }
}
