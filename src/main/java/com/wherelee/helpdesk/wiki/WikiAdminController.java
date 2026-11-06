package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class WikiAdminController {

    private final WikiRepository repository;
    private final WikiSeeder seeder;
    private final EvalService evalService;
    private final BlindQuestionService blindQuestionService;

    public WikiAdminController(
            WikiRepository repository,
            WikiSeeder seeder,
            EvalService evalService,
            BlindQuestionService blindQuestionService
    ) {
        this.repository = repository;
        this.seeder = seeder;
        this.evalService = evalService;
        this.blindQuestionService = blindQuestionService;
    }

    @PostMapping("/seed")
    public JobStatus.Snapshot seed(@RequestParam(defaultValue = "false") boolean force) {
        requirePlatformAdmin();
        return seeder.start(force);
    }

    @GetMapping("/seed/status")
    public JobStatus.Snapshot seedStatus() {
        requirePlatformAdmin();
        return seeder.status();
    }

    @PostMapping("/eval")
    public JobStatus.Snapshot eval(
            @RequestParam(defaultValue = "200") int sample,
            @RequestParam(defaultValue = "FACT") String kind
    ) {
        requirePlatformAdmin();
        String normalized = switch (kind == null ? "" : kind.toUpperCase()) {
            case "BLIND" -> "BLIND";
            case "REAL" -> "REAL";
            case "FEEDBACK" -> "FEEDBACK";
            default -> "FACT";
        };
        return evalService.start(Math.max(10, Math.min(sample, 2000)), normalized);
    }

    @PostMapping("/questions/blind")
    public java.util.Map<String, Object> generateBlindQuestions() {
        requirePlatformAdmin();
        return blindQuestionService.generate();
    }

    @GetMapping("/eval/status")
    public JobStatus.Snapshot evalStatus() {
        requirePlatformAdmin();
        return evalService.status();
    }

    @GetMapping("/index/status")
    public WikiDtos.IndexStatus indexStatus() {
        return new WikiDtos.IndexStatus(
                repository.countPages(),
                repository.countSections(),
                repository.countChunks(),
                repository.countEvalQuestions()
        );
    }

    @GetMapping("/stale-pages")
    public List<WikiDtos.PageView> stalePages(@RequestParam(defaultValue = "180") int days) {
        return repository.stalePages(Math.max(1, days), 50).stream()
                .map(WikiDtos.PageView::of)
                .toList();
    }

    private void requirePlatformAdmin() {
        TenantScope scope = TenantContext.get();
        if (scope == null || !scope.platformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "platform_admin_required");
        }
    }
}
