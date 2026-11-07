package com.wherelee.helpdesk.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record CreateTicketRequest(
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Size(max = 20000) String description,
            String priority
    ) {
    }

    public record AddMessageRequest(
            @NotBlank @Size(max = 20000) String content
    ) {
    }

    public record UpdateTriageRequest(
            String category,
            String priority,
            UUID assigneeId
    ) {
    }

    public record FeedbackRequest(
            @NotBlank String action,
            @Size(max = 20000) String editedContent
    ) {
    }

    public record FeedbackView(
            String action,
            UUID messageId
    ) {
    }

    public record TicketView(
            UUID id,
            String subject,
            String description,
            String status,
            String priority,
            String category,
            String triageSource,
            String triageReason,
            String customerName,
            String customerEmail,
            Integer csatRating,
            String csatComment,
            Instant csatAt,
            Instant firstResponseAt,
            Instant resolvedAt,
            Instant slaDueAt,
            boolean slaBreached,
            UUID assigneeId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static TicketView of(Ticket ticket) {
            return new TicketView(
                    ticket.id(), ticket.subject(), ticket.description(), ticket.status(),
                    ticket.priority(), ticket.category(), ticket.triageSource(), ticket.triageReason(),
                    ticket.customerName(), ticket.customerEmail(),
                    ticket.csatRating(), ticket.csatComment(), ticket.csatAt(),
                    ticket.firstResponseAt(), ticket.resolvedAt(), ticket.slaDueAt(), ticket.slaBreached(),
                    ticket.assigneeId(), ticket.createdAt(), ticket.updatedAt()
            );
        }
    }

    public record MessageView(
            UUID id,
            String authorType,
            UUID authorId,
            String content,
            String visibility,
            String citations,
            int tokens,
            Instant createdAt
    ) {
        public static MessageView of(TicketMessage message) {
            return new MessageView(
                    message.id(), message.authorType(), message.authorId(), message.content(),
                    message.visibility(), message.citationsJson(), message.tokens(), message.createdAt()
            );
        }
    }

    public record TicketDetail(TicketView ticket, List<MessageView> messages) {
    }
}
