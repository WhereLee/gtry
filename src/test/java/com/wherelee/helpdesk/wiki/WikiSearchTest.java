package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
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
class WikiSearchTest {

    private static final UUID ACME = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CLOUDWISE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    private WikiRepository repository;

    @Autowired
    private WikiIndexService indexService;

    @Autowired
    private WikiSearchService searchService;

    @Autowired
    private WikiPageService pageService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void publicPageIsFoundWithinItsTenantOnly() {
        UUID pageId = TenantContext.callWith(TenantScope.tenant(ACME), () -> {
            WikiSpace space = repository.createSpace(ACME, "it-" + UUID.randomUUID(),
                    "集成测试空间", "test");
            WikiPage page = repository.createPage(ACME, space.id(), "it-page-" + UUID.randomUUID(),
                    "耳机退货规则", "PUBLIC", null, Instant.now());
            WikiSection section = repository.createSection(ACME, page.id(), 0,
                    "耳机退货规则 > 适用条件",
                    "耳机拆封后不支持无理由退货，存在质量问题时可以申请退货。");
            indexService.indexSection(section.id());
            return page.id();
        });

        try {
            WikiSearchResult own = TenantContext.callWith(TenantScope.tenant(ACME),
                    () -> searchService.search("耳机拆封能退吗", 5, true));
            assertThat(own.hits()).isNotEmpty();
            assertThat(own.hits().get(0).pageTitle()).isEqualTo("耳机退货规则");

            WikiSearchResult other = TenantContext.callWith(TenantScope.tenant(CLOUDWISE),
                    () -> searchService.search("耳机拆封能退吗", 5, true));
            assertThat(other.hits()).isEmpty();
        } finally {
            TenantContext.runWith(TenantScope.tenant(ACME), () -> repository.deletePage(pageId));
        }
    }

    @Test
    void internalPageIsHiddenWhenRequested() {
        UUID pageId = TenantContext.callWith(TenantScope.tenant(ACME), () -> {
            WikiSpace space = repository.createSpace(ACME, "it-int-" + UUID.randomUUID(),
                    "内部空间", "test");
            WikiPage page = repository.createPage(ACME, space.id(), "it-int-page-" + UUID.randomUUID(),
                    "内部赔付口径", "INTERNAL", null, Instant.now());
            WikiSection section = repository.createSection(ACME, page.id(), 0,
                    "内部赔付口径 > 适用条件",
                    "内部赔付上限为订单金额的两倍，仅限主管审批。");
            indexService.indexSection(section.id());
            return page.id();
        });

        try {
            WikiSearchResult withoutInternal = TenantContext.callWith(TenantScope.tenant(ACME),
                    () -> searchService.search("赔付上限", 5, false));
            assertThat(withoutInternal.hits()).isEmpty();

            WikiSearchResult withInternal = TenantContext.callWith(TenantScope.tenant(ACME),
                    () -> searchService.search("赔付上限", 5, true));
            assertThat(withInternal.hits()).isNotEmpty();
        } finally {
            TenantContext.runWith(TenantScope.tenant(ACME), () -> repository.deletePage(pageId));
        }
    }

    @Test
    void pageCrudIndexesAndWritesAuditLog() {
        TenantScope admin = new TenantScope(
                ACME, UUID.fromString("00000000-0000-0000-0000-000000000021"),
                "admin@acme.test", "TENANT_ADMIN", false);

        UUID spaceId = TenantContext.callWith(admin, () ->
                repository.createSpace(ACME, "it-crud-" + UUID.randomUUID(), "CRUD 空间", "test").id());

        WikiDtos.PageDetail created = TenantContext.callWith(admin, () -> pageService.create(
                new WikiPageDtos.CreatePageRequest(
                        spaceId, "会员积分规则", "PUBLIC",
                        List.of(new WikiPageDtos.SectionInput("适用条件", "金卡会员消费一元累积两积分。"))
                )));
        UUID pageId = created.page().id();
        // Page creation now enqueues an outbox event; force indexing here so the
        // search assertions are deterministic without waiting for the consumer.
        TenantContext.runWith(admin, () -> pageService.reindex(pageId));
        TenantContext.runWith(admin, () -> {
            pageService.submit(pageId);
            pageService.approve(pageId);
        });

        try {
            assertThat(created.sections()).hasSize(1);
            WikiSearchResult found = TenantContext.callWith(TenantScope.tenant(ACME),
                    () -> searchService.search("金卡会员积分怎么算", 5, true));
            assertThat(found.hits()).isNotEmpty();
            assertThat(found.hits().get(0).pageId()).isEqualTo(pageId);

            WikiDtos.PageDetail updated = TenantContext.callWith(admin, () -> pageService.update(
                    pageId, new WikiPageDtos.UpdatePageRequest(
                            "会员积分规则 V2", "PUBLIC",
                            List.of(new WikiPageDtos.SectionInput("适用条件", "钻石会员消费一元累积三积分。"))
                    )));
            assertThat(updated.sections()).hasSize(1);
            assertThat(updated.page().title()).isEqualTo("会员积分规则 V2");

            Integer auditRows = TenantContext.callWith(TenantScope.tenant(ACME), () ->
                    jdbc.queryForObject(
                            "SELECT count(*) FROM audit_log WHERE target_id = ?",
                            Integer.class, pageId.toString()));
            assertThat(auditRows).isEqualTo(4);
        } finally {
            TenantContext.runWith(admin, () -> pageService.delete(pageId));
        }
    }
}
