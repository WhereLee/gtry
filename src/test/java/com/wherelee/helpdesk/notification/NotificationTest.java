package com.wherelee.helpdesk.notification;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.user.AppUser;
import com.wherelee.helpdesk.user.UserRepository;
import com.wherelee.helpdesk.wiki.WikiDtos;
import com.wherelee.helpdesk.wiki.WikiPageDtos;
import com.wherelee.helpdesk.wiki.WikiPageService;
import com.wherelee.helpdesk.wiki.WikiRepository;
import com.wherelee.helpdesk.wiki.WikiSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class NotificationTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private WikiRepository wikiRepository;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void submitAndApproveCreateNotificationsForTheOtherSide() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantScope tenantScope = TenantScope.tenant(ACME);
        AppUser adminA = TenantContext.callWith(tenantScope, () -> users.create(
                ACME, "notify-a-" + suffix + "@acme.test", passwordEncoder.encode("password123"),
                "Notify A", "TENANT_ADMIN"));
        AppUser adminB = TenantContext.callWith(tenantScope, () -> users.create(
                ACME, "notify-b-" + suffix + "@acme.test", passwordEncoder.encode("password123"),
                "Notify B", "TENANT_ADMIN"));
        TenantScope scopeA = new TenantScope(ACME, adminA.id(), adminA.email(), "TENANT_ADMIN", false);
        TenantScope scopeB = new TenantScope(ACME, adminB.id(), adminB.email(), "TENANT_ADMIN", false);
        WikiSpace space = null;
        UUID pageId = null;
        try {
            space = TenantContext.callWith(scopeA, () ->
                    wikiRepository.createSpace(ACME, "notify-" + suffix, "通知测试", "test"));
            WikiSpace finalSpace = space;
            WikiDtos.PageDetail page = TenantContext.callWith(scopeA, () -> pageService.create(
                    new WikiPageDtos.CreatePageRequest(
                            finalSpace.id(), "通知测试页面", "PUBLIC",
                            List.of(new WikiPageDtos.SectionInput("规则", "内容")))));
            pageId = page.page().id();

            UUID finalPageId = pageId;
            TenantContext.runWith(scopeA, () -> pageService.submit(finalPageId));
            List<NotificationService.NotificationView> forB = TenantContext.callWith(
                    scopeB, () -> notificationService.list(false, 50));
            assertThat(forB).anyMatch(item -> "WIKI_REVIEW".equals(item.type()));

            TenantContext.runWith(scopeB, () -> pageService.approve(finalPageId));
            List<NotificationService.NotificationView> forA = TenantContext.callWith(
                    scopeA, () -> notificationService.list(false, 50));
            assertThat(forA).anyMatch(item -> "WIKI_APPROVED".equals(item.type()));
        } finally {
            UUID cleanupPageId = pageId;
            WikiSpace cleanupSpace = space;
            TenantContext.runWith(tenantScope, () -> {
                if (cleanupPageId != null) {
                    jdbc.update("DELETE FROM wiki_page WHERE id = ?", cleanupPageId);
                }
                if (cleanupSpace != null) {
                    jdbc.update("DELETE FROM wiki_space WHERE id = ?", cleanupSpace.id());
                }
                jdbc.update("DELETE FROM notification WHERE user_id IN (?, ?)", adminA.id(), adminB.id());
                jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", adminA.id(), adminB.id());
            });
        }
    }
}
