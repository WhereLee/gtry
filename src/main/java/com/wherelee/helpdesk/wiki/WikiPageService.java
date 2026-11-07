package com.wherelee.helpdesk.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.wherelee.helpdesk.outbox.OutboxRepository;
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
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WikiPageService(
            WikiRepository repository,
            WikiIndexService indexService,
            OutboxRepository outbox,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.indexService = indexService;
        this.outbox = outbox;
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
                    null
            );
            insertSections(scope.tenantId(), page.id(), request.sections());
            repository.updatePageStatus(page.id(), "DRAFT", null);
            saveVersion(scope, page.id(), "DRAFT", request.title(),
                    normalizeVisibility(request.visibility()), request.sections());
            audit(scope, "wiki.page.create", page.id(), Map.of("title", request.title()));
            enqueueIndex(scope.tenantId(), page.id());
            return page.id();
        });
        if (pageId == null) {
            throw new IllegalStateException("page transaction returned no id");
        }
        return detail(pageId);
    }

    public WikiDtos.PageDetail update(UUID pageId, WikiPageDtos.UpdatePageRequest request) {
        TenantScope scope = requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));

        transactionTemplate.executeWithoutResult(status -> {
            repository.updatePage(
                    pageId, request.title(), normalizeVisibility(request.visibility()), null);
            repository.deleteSections(pageId);
            insertSections(scope.tenantId(), pageId, request.sections());
            repository.updatePageStatus(pageId, "DRAFT", null);
            saveVersion(scope, pageId, "DRAFT", request.title(),
                    normalizeVisibility(request.visibility()), request.sections());
            audit(scope, "wiki.page.update", pageId, Map.of("title", request.title()));
            enqueueIndex(scope.tenantId(), pageId);
        });
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

    public List<WikiDtos.PageVersionView> versions(UUID pageId) {
        requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        return repository.listPageVersions(pageId).stream()
                .map(WikiDtos.PageVersionView::of)
                .toList();
    }

    public WikiDtos.PageVersionDetail version(UUID pageId, int version) {
        requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        WikiPageVersion snapshot = repository.findPageVersion(pageId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "version_not_found"));
        return new WikiDtos.PageVersionDetail(
                WikiDtos.PageVersionView.of(snapshot), parseSections(snapshot.sectionsJson()));
    }

    public WikiDtos.PageDetail rollback(UUID pageId, int version) {
        TenantScope scope = requireAdmin();
        repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        WikiPageVersion snapshot = repository.findPageVersion(pageId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "version_not_found"));
        List<WikiPageDtos.SectionInput> sections = parseSections(snapshot.sectionsJson());
        if (sections.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "version_has_no_sections");
        }
        transactionTemplate.executeWithoutResult(status -> {
            repository.updatePage(pageId, snapshot.title(), snapshot.visibility(), null);
            repository.updatePageStatus(pageId, "PUBLISHED", Instant.now());
            repository.deleteSections(pageId);
            insertSections(scope.tenantId(), pageId, sections);
            saveVersion(scope, pageId, "PUBLISHED", snapshot.title(), snapshot.visibility(), sections);
            audit(scope, "wiki.page.rollback", pageId,
                    Map.of("version", version, "title", snapshot.title()));
            enqueueIndex(scope.tenantId(), pageId);
        });
        return detail(pageId);
    }

    public WikiDtos.PageDetail submit(UUID pageId) {
        return changeStatus(pageId, "REVIEW", "wiki.page.submit", false);
    }

    public WikiDtos.PageDetail approve(UUID pageId) {
        return changeStatus(pageId, "PUBLISHED", "wiki.page.approve", true);
    }

    public WikiDtos.PageDetail reject(UUID pageId) {
        return changeStatus(pageId, "DRAFT", "wiki.page.reject", false);
    }

    public int importQuestions(List<WikiPageDtos.ImportQuestion> questions, boolean replace) {
        TenantScope scope = requireAdmin();
        if (questions == null || questions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "questions_required");
        }
        List<WikiRepository.QuestionRow> rows = new ArrayList<>(questions.size());
        for (WikiPageDtos.ImportQuestion input : questions) {
            if (input == null || input.question() == null || input.question().isBlank() || input.pageId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_required");
            }
            repository.findPage(input.pageId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
            UUID sectionId = repository.findFirstSectionId(input.pageId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "page_has_no_sections"));
            rows.add(new WikiRepository.QuestionRow(
                    UUID.randomUUID(), scope.tenantId(), sectionId, input.question().trim(), "REAL"));
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (replace) {
                repository.deleteQuestionsByKind("REAL");
            }
            repository.insertQuestions(rows);
            audit(scope, "wiki.questions.import", null, Map.of("count", rows.size(), "replace", replace));
        });
        return rows.size();
    }

    private void insertSections(UUID tenantId, UUID pageId, List<WikiPageDtos.SectionInput> sections) {
        int seq = 0;
        for (WikiPageDtos.SectionInput input : sections) {
            repository.createSection(
                    tenantId, pageId, seq++, input.heading().trim(), input.content().trim());
        }
    }

    private void saveVersion(
            TenantScope scope, UUID pageId, String status, String title,
            String visibility, List<WikiPageDtos.SectionInput> sections
    ) {
        try {
            String json = objectMapper.writeValueAsString(sections);
            int version = repository.nextPageVersion(pageId);
            repository.insertPageVersion(
                    scope.tenantId(), pageId, version, title, visibility, status, json, scope.userId());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("version snapshot failed", ex);
        }
    }

    private List<WikiPageDtos.SectionInput> parseSections(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("version snapshot is invalid", ex);
        }
    }

    private WikiDtos.PageDetail changeStatus(
            UUID pageId, String targetStatus, String action, boolean reviewed
    ) {
        TenantScope scope = requireAdmin();
        WikiPage page = repository.findPage(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        if ("PUBLISHED".equals(page.status()) && "REVIEW".equals(targetStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "page_already_published");
        }
        if ("REVIEW".equals(targetStatus) && !"DRAFT".equals(page.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid_page_status");
        }
        if (("PUBLISHED".equals(targetStatus) || "DRAFT".equals(targetStatus))
                && !"REVIEW".equals(page.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid_page_status");
        }
        transactionTemplate.executeWithoutResult(status -> {
            repository.updatePageStatus(pageId, targetStatus, reviewed ? Instant.now() : null);
            audit(scope, action, pageId, Map.of("status", targetStatus));
        });
        return detail(pageId);
    }

    private void enqueueIndex(UUID tenantId, UUID pageId) {
        outbox.insert(
                tenantId, "wiki_page", pageId, "WIKI_PAGE_INDEX",
                "{\"pageId\":\"" + pageId + "\"}");
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
                    "wiki_page", targetId == null ? null : targetId.toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (Exception ex) {
            throw new IllegalStateException("audit write failed", ex);
        }
    }
}
