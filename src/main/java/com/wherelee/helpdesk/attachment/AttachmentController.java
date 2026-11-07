package com.wherelee.helpdesk.attachment;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping("/tickets/{ticketId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentService.AttachmentView upload(
            @PathVariable UUID ticketId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "PUBLIC") String visibility
    ) {
        TenantScope scope = requireScope();
        return attachments.store(scope.tenantId(), ticketId, file, visibility);
    }

    @GetMapping("/tickets/{ticketId}/attachments")
    public List<AttachmentService.AttachmentView> list(@PathVariable UUID ticketId) {
        requireScope();
        return attachments.list(ticketId, false);
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        requireScope();
        return toResponse(attachments.load(id));
    }

    public static ResponseEntity<Resource> toResponse(AttachmentService.Download download) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.attachment().filename(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType(download.attachment().contentType()))
                .body(download.resource());
    }

    private TenantScope requireScope() {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        return scope;
    }
}
