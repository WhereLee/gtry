package com.wherelee.helpdesk.platform;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "helpdesk.llm.provider=mock",
        "helpdesk.embedding.provider=mock",
        "helpdesk.rerank.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "HELPDESK_IT", matches = "true")
class PlatformAuditTest {

    private static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private PlatformService platformService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void platformAuditCanBeFilteredByActionAndTarget() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "audit-" + suffix;
        PlatformDtos.TenantSummary created = TenantContext.callWith(
                TenantScope.platform(PLATFORM),
                () -> platformService.createTenant(new PlatformDtos.CreateTenantRequest(
                        slug, "Audit Test", "free", 1000L,
                        "admin-" + suffix + "@audit.test", "password123", "Audit Admin")));
        try {
            var rows = TenantContext.callWith(
                    TenantScope.platform(PLATFORM),
                    () -> platformService.auditLogs("tenant.create", "tenant", created.id().toString(), 10));
            assertThat(rows).isNotEmpty();
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("action")).isEqualTo("tenant.create");
            assertThat(row.get("targetId")).isEqualTo(created.id().toString());
            assertThat(String.valueOf(row.get("detail"))).contains(slug);
        } finally {
            jdbc.update("DELETE FROM tenant WHERE id = ?", created.id());
        }
    }
}
