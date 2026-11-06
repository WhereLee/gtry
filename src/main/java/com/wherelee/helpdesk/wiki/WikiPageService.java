package com.wherelee.helpdesk.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WikiPageService {

    private final WikiRepository repository;
    private final WikiIndexService indexService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WikiPageService(
            WikiRepository repository,
            WikiIndexService indexService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public WikiDtos.PageDetail create(WikiPageDtos.CreatePageRequest request) {
        TenantScope scope = requireAdmin();
        UUID spaceId = request.spaceId();
        if (spaceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "space_id_required");
        }
        repository.findSpaceById(spaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space_not_found"));

        UUID pageId = transactionTemplate.execute(status -> {
            WikiPage page = repository.createPage(
                    scope.tenantId(), spaceId,
                    "page-" + UUID.randomUUID().toString().substring(0, 8),
                    request.title(),
                    normalizeVisibility(request.visibility()),
                    scope.userId(),
                    Instant.now()
            );
            insertSections(scope.tenantId(), page.id(), request.sections());
            audit(scope, "wiki.page.create", page.id(), Map.of("title", request.title()));
            return page.id();
        });
        if (pageId == null) {
            throw new IllegalStateException("page transaction returned no id");
        }
        indexService.indexPage(pageId);
        return detail(pageId);
    }

    public WikiDtos.PageDetail update(UUID pageId, WikiPageDtos.UpdatePageRequest request) {
        TenantScope scope = requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));

        transactionTemplate.executeWithoutResult(status -> {
            repository.updatePage(
                    pageId, request.title(), normalizeVisibility(request.visibility()), Instant.now());
            repository.deleteSections(pageId);
            insertSections(scope.tenantId(), pageId, request.sections());
            audit(scope, "wiki.page.update", pageId, Map.of("title", request.title()));
        });
        indexService.indexPage(pageId);
        return detail(pageId);
    }

    public void delete(UUID pageId) {
        TenantScope scope = requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        transactionTemplate.executeWithoutResult(status -> {
            audit(scope, "wiki.page.delete", pageId, Map.of());
            repository.deletePage(pageId);
        });
    }

    public int reindex(UUID pageId) {
        requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        return indexService.indexPage(pageId);
    }

    private void insertSections(UUID tenantId, UUID pageId, List<WikiPageDtos.SectionInput> sections) {
        int seq = 0;
        for (WikiPageDtos.SectionInput input : sections) {
            repository.createSection(
                    tenantId, pageId, seq++, input.heading().trim(), input.content().trim());
        }
    }

    private WikiDtos.PageDetail detail(UUID pageId) {
        WikiPage page = repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        List<WikiDtos.SectionView> sections = new ArrayList<>();
        for (WikiSection section : repository.listSections(pageId)) {
            sections.add(WikiDtos.SectionView.of(section));
        }
        return new WikiDtos.PageDetail(WikiDtos.PageView.of(page), sections);
    }

    private String normalizeVisibility(String value) {
        return "INTERNAL".equalsIgnoreCase(value) ? "INTERNAL" : "PUBLIC";
    }

    private TenantScope requireAdmin() {
        TenantScope scope = TenantContext.get();
        if (scope == null || (!scope.platformAdmin() && !"TENANT_ADMIN".equals(scope.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_admin_required");
        }
        return scope;
    }

    private void audit(TenantScope scope, String action, UUID targetId, Map<String, Object> detail) {
        try {
            repository.audit(
                    scope.tenantId(), scope.userId(), action,
                    "wiki_page", targetId.toString(), objectMapper.writeValueAsString(detail));
        } catch (Exception ex) {
            throw new IllegalStateException("audit write failed", ex);
        }
    }
}
