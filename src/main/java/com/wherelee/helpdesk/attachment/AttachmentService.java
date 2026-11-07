package com.wherelee.helpdesk.attachment;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.ticket.TicketRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "application/pdf", "text/plain"
    );

    private final AttachmentRepository repository;
    private final TicketRepository tickets;
    private final HelpdeskProperties.Attachments config;
    private final Path baseDir;

    public AttachmentService(
            AttachmentRepository repository,
            TicketRepository tickets,
            HelpdeskProperties properties
    ) {
        this.repository = repository;
        this.tickets = tickets;
        this.config = properties.getAttachments();
        this.baseDir = Paths.get(config.getDir()).toAbsolutePath().normalize();
    }

    public AttachmentView store(
            UUID tenantId, UUID ticketId, MultipartFile file, String visibility
    ) {
        tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        validate(file);
        String safeName = safeFilename(file.getOriginalFilename());
        String storageKey = tenantId + "/" + ticketId + "/" + UUID.randomUUID() + "-" + safeName;
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
            AttachmentRepository.Attachment attachment = repository.insert(
                    tenantId, ticketId, safeName, file.getContentType(),
                    file.getSize(), storageKey, normalizeVisibility(visibility));
            return AttachmentView.of(attachment);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "attachment_store_failed", ex);
        } catch (RuntimeException ex) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw ex;
        }
    }

    public List<AttachmentView> list(UUID ticketId, boolean publicOnly) {
        return repository.listByTicket(ticketId, publicOnly).stream()
                .map(AttachmentView::of)
                .toList();
    }

    public Download load(UUID attachmentId) {
        AttachmentRepository.Attachment attachment = repository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment_not_found"));
        Path path = resolve(attachment.storageKey());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment_file_missing");
        }
        Resource resource = new FileSystemResource(path);
        return new Download(attachment, resource);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file_required");
        }
        if (file.getSize() > config.getMaxSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file_too_large");
        }
        String contentType = file.getContentType() == null
                ? "application/octet-stream"
                : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file_type_not_allowed");
        }
    }

    private Path resolve(String storageKey) {
        Path path = baseDir.resolve(storageKey).normalize();
        if (!path.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_storage_key");
        }
        return path;
    }

    private String safeFilename(String original) {
        String name = original == null ? "file" : Paths.get(original).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "file" : name;
    }

    private String normalizeVisibility(String visibility) {
        return "INTERNAL".equalsIgnoreCase(visibility) ? "INTERNAL" : "PUBLIC";
    }

    public record AttachmentView(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String visibility,
            Instant createdAt
    ) {
        static AttachmentView of(AttachmentRepository.Attachment attachment) {
            return new AttachmentView(
                    attachment.id(), attachment.filename(), attachment.contentType(),
                    attachment.sizeBytes(), attachment.visibility(), attachment.createdAt());
        }
    }

    public record Download(AttachmentRepository.Attachment attachment, Resource resource) {
    }
}
