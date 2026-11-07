package com.wherelee.helpdesk.tool;

import com.wherelee.helpdesk.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class ToolCallController {

    private final ToolCallLogRepository repository;

    public ToolCallController(ToolCallLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}/tool-calls")
    public List<Map<String, Object>> list(@PathVariable UUID id) {
        if (TenantContext.get() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        return repository.listByTicket(id);
    }
}
