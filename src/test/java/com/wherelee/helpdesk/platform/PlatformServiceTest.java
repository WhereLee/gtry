package com.wherelee.helpdesk.platform;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.wiki.JobStatus;
import com.wherelee.helpdesk.wiki.WikiRepository;
import com.wherelee.helpdesk.wiki.WikiSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
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
class PlatformServiceTest {

    private static final UUID PLATFORM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private PlatformService platformService;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private WikiRepository wikiRepository;

    @Test
    void createsTenantWithDefaultSpaceThenExportsAndDeletesIt() throws InterruptedException {
        String slug = "it-" + UUID.randomUUID().toString().substring(0, 8);
        PlatformDtos.TenantSummary created = TenantContext.callWith(
                TenantScope.platform(PLATFORM),
                () -> platformService.createTenant(new PlatformDtos.CreateTenantRequest(
                        slug, "IT Tenant", "pro", 10_000L,
                        "admin@" + slug + ".test", "password123", "IT Admin"))
        );
        try {
            assertThat(created.slug()).isEqualTo(slug);
            assertThat(created.userCount()).isEqualTo(1);

            List<WikiSpace> spaces = TenantContext.callWith(
                    TenantScope.tenant(created.id()), wikiRepository::listSpaces);
            assertThat(spaces).extracting(WikiSpace::slug).contains("default");

            PlatformDtos.TenantSummary suspended = TenantContext.callWith(
                    TenantScope.platform(PLATFORM),
                    () -> platformService.updateStatus(created.id(), "SUSPENDED"));
            assertThat(suspended.status()).isEqualTo("SUSPENDED");

            PlatformService.TenantExport export = TenantContext.callWith(
                    TenantScope.platform(PLATFORM),
                    () -> platformService.exportTenant(created.id()));
            assertThat(export.data()).containsKeys("tenant", "users", "wikiSpaces");

            JobStatus.Snapshot job = TenantContext.callWith(
                    TenantScope.platform(PLATFORM),
                    () -> platformService.deleteTenant(created.id(), slug));
            for (int i = 0; i < 50 && job.state() == JobStatus.State.RUNNING; i++) {
                Thread.sleep(100);
                job = TenantContext.callWith(
                        TenantScope.platform(PLATFORM),
                        () -> platformService.deletionStatus(created.id()));
            }
            assertThat(job.state()).isEqualTo(JobStatus.State.DONE);
            assertThat(TenantContext.callWith(
                    TenantScope.platform(PLATFORM), () -> tenants.findById(created.id()))).isEmpty();
        } finally {
            TenantContext.runWith(TenantScope.platform(PLATFORM), () -> tenants.delete(created.id()));
        }
    }
}
