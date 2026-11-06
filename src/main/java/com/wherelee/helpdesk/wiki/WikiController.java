package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
public class WikiController {

    private final WikiRepository repository;
    private final WikiSearchService searchService;
    private final WikiPageService pageService;
    private final HelpdeskProperties.Wiki config;

    public WikiController(
            WikiRepository repository,
            WikiSearchService searchService,
            WikiPageService pageService,
            HelpdeskProperties properties
    ) {
        this.repository = repository;
        this.searchService = searchService;
        this.pageService = pageService;
        this.config = properties.getWiki();
    }

    @PostMapping({"/api/wiki/search", "/api/kb/search"})
    public WikiDtos.SearchResponse search(@Valid @RequestBody WikiDtos.SearchRequest request) {
        int topK = request.topK() == null ? 5 : Math.max(1, Math.min(request.topK(), 20));
        boolean includeInternal = request.includeInternal() == null
                ? config.isIncludeInternalDefault()
                : request.includeInternal();
        return WikiDtos.SearchResponse.of(searchService.search(request.query(), topK, includeInternal));
    }

    @GetMapping("/api/wiki/spaces")
    public List<WikiDtos.SpaceView> spaces() {
        return repository.listSpaces().stream().map(WikiDtos.SpaceView::of).toList();
    }

    @GetMapping("/api/wiki/pages")
    public List<WikiDtos.PageView> pages(
            @RequestParam(required = false) UUID spaceId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return repository.listPages(spaceId, safeLimit).stream().map(WikiDtos.PageView::of).toList();
    }

    @GetMapping("/api/wiki/pages/{id}")
    public WikiDtos.PageDetail page(@PathVariable UUID id) {
        WikiPage page = repository.findPage(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "page_not_found"));
        List<WikiDtos.SectionView> sections = repository.listSections(id).stream()
                .map(WikiDtos.SectionView::of)
                .toList();
        return new WikiDtos.PageDetail(WikiDtos.PageView.of(page), sections);
    }

    @PostMapping("/api/wiki/pages")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public WikiDtos.PageDetail createPage(@Valid @RequestBody WikiPageDtos.CreatePageRequest request) {
        return pageService.create(request);
    }

    @PutMapping("/api/wiki/pages/{id}")
    public WikiDtos.PageDetail updatePage(
            @PathVariable UUID id,
            @Valid @RequestBody WikiPageDtos.UpdatePageRequest request
    ) {
        return pageService.update(id, request);
    }

    @DeleteMapping("/api/wiki/pages/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePage(@PathVariable UUID id) {
        pageService.delete(id);
    }

    @PostMapping("/api/wiki/pages/{id}/reindex")
    public java.util.Map<String, Object> reindex(@PathVariable UUID id) {
        return java.util.Map.of("sections", pageService.reindex(id));
    }
}
