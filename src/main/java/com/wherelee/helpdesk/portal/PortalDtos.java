package com.wherelee.helpdesk.portal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PortalDtos {

    private PortalDtos() {
    }

    public record CreateTicketRequest(
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Size(max = 20000) String description,
            @Size(max = 128) String name,
            @Email @Size(max = 255) String email,
            @Size(max = 255) String website
    ) {
    }

    public record CreateTicketResponse(
            UUID ticketId,
            String token,
            Instant expiresAt,
            String status
    ) {
    }

    public record ReplyRequest(
            @NotBlank @Size(max = 20000) String content
    ) {
    }

    public record CsatRequest(
            @Min(1) @Max(5) int rating,
            @Size(max = 1000) String comment
    ) {
    }

    public record PublicMessageView(
            String authorType,
            String content,
            Instant createdAt
    ) {
    }

    public record PublicTicketView(
            UUID id,
            String subject,
            String status,
            String category,
            Instant createdAt,
            Instant updatedAt,
            Integer csatRating,
            String csatComment,
            List<PublicMessageView> messages
    ) {
    }
}
