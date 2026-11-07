package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
class TenantAiConfigTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Autowired
    private TenantAiConfigService configService;

    @Test
    void tenantAdminCanUpdateModelTopKAndKillSwitch() {
        TenantScope admin = new TenantScope(ACME, ADMIN, "admin@acme.test", "TENANT_ADMIN", false);
        TenantContext.runWith(admin, () -> {
            try {
                TenantAiConfigRepository.Config updated = configService.update(
                        new AiConfigDtos.UpdateAiConfigRequest(
                                "deepseek-chat", 3, "回答时优先说明时效", false));
                assertThat(updated.model()).isEqualTo("deepseek-chat");
                assertThat(updated.topK()).isEqualTo(3);
                assertThat(updated.enabled()).isFalse();
                assertThat(configService.current().extraInstructions()).contains("时效");
            } finally {
                configService.update(new AiConfigDtos.UpdateAiConfigRequest(
                        "deepseek-flash", 5, "", true));
            }
        });
    }
}
