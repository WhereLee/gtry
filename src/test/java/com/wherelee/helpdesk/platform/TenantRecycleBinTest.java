package com.wherelee.helpdesk.platform;

import com.wherelee.helpdesk.auth.AuthService;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.wiki.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "helpdesk.llm.provider=mock",
        "helpdesk.embedding.provider=mock",
        "helpdesk.rerank.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "HELPDESK_IT", matches = "true")
class TenantRecycleBinTest {

    private static final UUID PLATFORM_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private PlatformService platformService;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void softDeleteRestoreAndPurgeExpired() {
        TenantScope platform = TenantScope.platform(PLATFORM_TENANT);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = "recycle-" + suffix;
        String email = "admin-" + suffix + "@recycle.test";
        PlatformDtos.TenantSummary created = TenantContext.callWith(platform, () ->
                platformService.createTenant(new PlatformDtos.CreateTenantRequest(
                        slug, "Recycle Test", "free", 1000L, email, "password123", "Recycle Admin")));
        UUID tenantId = created.id();
        try {
            JobStatus.Snapshot deleted = TenantContext.callWith(platform, () ->
                    platformService.deleteTenant(tenantId, slug));
            assertThat(deleted.state()).isEqualTo(JobStatus.State.DONE);
            Tenant softDeleted = tenants.findById(tenantId).orElseThrow();
            assertThat(softDeleted.status()).isEqualTo("DELETED");
            assertThatThrownBy(() -> authService.login(slug, email, "password123"))
                    .isInstanceOf(ResponseStatusException.class);

            PlatformDtos.TenantSummary restored = TenantContext.callWith(platform, () ->
                    platformService.restoreTenant(tenantId));
            assertThat(restored.status()).isEqualTo("ACTIVE");

            TenantContext.runWith(platform, () -> platformService.deleteTenant(tenantId, slug));
            jdbc.update("UPDATE tenant SET deleted_at = now() - interval '31 days' WHERE id = ?", tenantId);
            assertThat(platformService.purgeExpired(30)).isGreaterThanOrEqualTo(1);
            assertThat(tenants.findById(tenantId)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
        }
    }
}
