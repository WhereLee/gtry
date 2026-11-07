package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class WikiVersionTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private WikiRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void versionsAreAppendOnlyAndApprovalControlsSearch() {
        TenantScope scope = TenantScope.platform(ACME);
        TenantContext.runWith(scope, () -> {
            WikiSpace space = repository.createSpace(
                    ACME, "ver-" + UUID.randomUUID().toString().substring(0, 8),
                    "版本测试空间", "test");
            UUID pageId = null;
            String token = "versiontoken" + UUID.randomUUID().toString().replace("-", "");
            try {
                WikiDtos.PageDetail created = pageService.create(new WikiPageDtos.CreatePageRequest(
                        space.id(), "版本测试页面", "PUBLIC",
                        List.of(new WikiPageDtos.SectionInput("规则", "第一版 " + token))));
                UUID createdPageId = created.page().id();
                pageId = createdPageId;
                assertThat(created.page().status()).isEqualTo("DRAFT");
                assertThat(pageService.versions(pageId)).hasSize(1);

                pageService.reindex(pageId);
                String tsQuery = ChineseText.tsQuery(token);
                assertThat(repository.searchFullText(tsQuery, 10, true)).isEmpty();

                pageService.submit(pageId);
                WikiDtos.PageDetail approved = pageService.approve(pageId);
                assertThat(approved.page().status()).isEqualTo("PUBLISHED");
                assertThat(repository.searchFullText(tsQuery, 10, true))
                        .anyMatch(hit -> hit.pageId().equals(createdPageId));

                WikiDtos.PageDetail updated = pageService.update(pageId, new WikiPageDtos.UpdatePageRequest(
                        "版本测试页面 v2", "PUBLIC",
                        List.of(new WikiPageDtos.SectionInput("规则", "第二版 " + token))));
                assertThat(updated.page().status()).isEqualTo("DRAFT");
                assertThat(pageService.versions(pageId)).hasSize(2);

                WikiDtos.PageDetail rolledBack = pageService.rollback(pageId, 1);
                assertThat(rolledBack.page().status()).isEqualTo("PUBLISHED");
                assertThat(rolledBack.sections()).extracting(WikiDtos.SectionView::content)
                        .containsExactly("第一版 " + token);
                assertThat(pageService.versions(pageId)).hasSize(3);
            } finally {
                if (pageId != null) {
                    jdbc.update("DELETE FROM wiki_page WHERE id = ?", pageId);
                }
                jdbc.update("DELETE FROM wiki_space WHERE id = ?", space.id());
            }
        });
    }
}
