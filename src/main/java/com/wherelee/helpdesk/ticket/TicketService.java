package com.wherelee.helpdesk.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.ai.RagService;
import com.wherelee.helpdesk.ai.ClientDisconnectedException;
import com.wherelee.helpdesk.ai.AiFeedbackRepository;
import com.wherelee.helpdesk.events.AiFeedbackAcceptedEvent;
import com.wherelee.helpdesk.quota.QuotaService;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository tickets;
    private final TicketTriageService triageService;
    private final SlaService slaService;
    private final UserRepository users;
    private final AiFeedbackRepository feedbackRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RagService ragService;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final Executor aiExecutor;
    private final Counter aiDoneCounter;
    private final Counter aiPartialCounter;
    private final Counter aiErrorCounter;

    public TicketService(
            TicketRepository tickets,
            TicketTriageService triageService,
            SlaService slaService,
            UserRepository users,
            AiFeedbackRepository feedbackRepository,
            ApplicationEventPublisher eventPublisher,
            RagService ragService,
            QuotaService quotaService,
            ObjectMapper objectMapper,
            @Qualifier("aiExecutor") Executor aiExecutor,
            MeterRegistry meterRegistry
    ) {
        this.tickets = tickets;
        this.triageService = triageService;
        this.slaService = slaService;
        this.users = users;
        this.feedbackRepository = feedbackRepository;
        this.eventPublisher = eventPublisher;
        this.ragService = ragService;
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
        this.aiExecutor = aiExecutor;
        this.aiDoneCounter = meterRegistry.counter("helpdesk_ai_stream_total", "result", "done");
        this.aiPartialCounter = meterRegistry.counter("helpdesk_ai_stream_total", "result", "partial");
        this.aiErrorCounter = meterRegistry.counter("helpdesk_ai_stream_total", "result", "error");
    }

    public TicketDtos.TicketView create(String subject, String description, String priority) {
        return create(subject, description, priority, null, null);
    }

    public TicketDtos.TicketView create(
            String subject, String description, String priority,
            String customerName, String customerEmail
    ) {
        TenantScope scope = requireScope();
        TicketTriageService.TriageResult triage = triageService.triage(
                subject, description, scope.tenantId());
        String effectivePriority = priority == null || priority.isBlank()
                ? triage.priority()
                : priority.toUpperCase();
        Ticket ticket = tickets.create(
                scope.tenantId(), subject, description, effectivePriority,
                triage.category(), triage.source(), triage.reason(), triage.assigneeId(),
                customerName, customerEmail, slaService.dueAt(effectivePriority));
        tickets.addMessage(scope.tenantId(), ticket.id(), "CUSTOMER", null, description, "[]", 0);
        return TicketDtos.TicketView.of(ticket);
    }

    public List<TicketDtos.TicketView> list(String status) {
        return tickets.list(status, 100).stream().map(TicketDtos.TicketView::of).toList();
    }

    public TicketDtos.TicketPage page(String status, String query, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        long total = tickets.count(status, query);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        int safePage = Math.min(Math.max(1, page), Math.max(1, totalPages));
        long offset = (long) (safePage - 1) * safeSize;
        List<TicketDtos.TicketView> items = tickets
                .list(status, query, safeSize, offset)
                .stream()
                .map(TicketDtos.TicketView::of)
                .toList();
        boolean hasNext = (long) safePage * safeSize < total;
        return new TicketDtos.TicketPage(items, safePage, safeSize, total, totalPages, hasNext);
    }

    public TicketDtos.TicketDetail get(UUID id) {
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        List<TicketDtos.MessageView> messages = tickets.listMessages(id).stream()
                .map(TicketDtos.MessageView::of)
                .toList();
        return new TicketDtos.TicketDetail(TicketDtos.TicketView.of(ticket), messages);
    }

    public TicketDtos.MessageView addMessage(UUID id, String content) {
        TenantScope scope = requireScope();
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        TicketMessage message = tickets.addMessage(
                scope.tenantId(), id, "AGENT", scope.userId(), content, "[]", 0);
        tickets.markFirstResponse(id);
        if (!"PENDING".equals(ticket.status())) {
            tickets.updateStatus(id, "PENDING");
        } else {
            tickets.touch(id);
        }
        return TicketDtos.MessageView.of(message);
    }

    public TicketDtos.MessageView addInternalNote(UUID id, String content) {
        TenantScope scope = requireScope();
        tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        TicketMessage message = tickets.addMessage(
                scope.tenantId(), id, "AGENT", scope.userId(), content, "[]", 0, "INTERNAL");
        tickets.touch(id);
        return TicketDtos.MessageView.of(message);
    }

    public TicketDtos.FeedbackView feedback(
            UUID ticketId, UUID messageId, TicketDtos.FeedbackRequest request) {
        TenantScope scope = requireScope();
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        TicketMessage message = tickets.findMessage(ticketId, messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message_not_found"));
        if (!"AI".equals(message.authorType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not_ai_message");
        }
        String action = request.action().toUpperCase(Locale.ROOT);
        if (!List.of("ADOPT", "EDIT", "REJECT").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_feedback_action");
        }
        String edited = null;
        if ("ADOPT".equals(action)) {
            tickets.addMessage(
                    scope.tenantId(), ticketId, "AGENT", scope.userId(),
                    message.content(), "[]", 0, "PUBLIC");
            tickets.markFirstResponse(ticketId);
            if (!"PENDING".equals(ticket.status())) {
                tickets.updateStatus(ticketId, "PENDING");
            }
        } else if ("EDIT".equals(action)) {
            if (request.editedContent() == null || request.editedContent().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "edited_content_required");
            }
            edited = request.editedContent().trim();
            tickets.addMessage(
                    scope.tenantId(), ticketId, "AGENT", scope.userId(),
                    edited, "[]", 0, "PUBLIC");
            tickets.markFirstResponse(ticketId);
            if (!"PENDING".equals(ticket.status())) {
                tickets.updateStatus(ticketId, "PENDING");
            }
        }
        feedbackRepository.insert(
                scope.tenantId(), ticketId, messageId, scope.userId(),
                action, message.content(), edited);
        if (List.of("ADOPT", "EDIT").contains(action)) {
            eventPublisher.publishEvent(new AiFeedbackAcceptedEvent(
                    scope.tenantId(), ticketId, messageId, action,
                    ticket.subject() + "\n" + ticket.description(),
                    message.citationsJson()));
        }
        return new TicketDtos.FeedbackView(action, messageId);
    }

    public TicketDtos.TicketView retriage(UUID id) {
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        TicketTriageService.TriageResult triage = triageService.triage(
                ticket.subject(), ticket.description(), ticket.tenantId());
        tickets.updateTriage(
                id, triage.category(), triage.priority(), triage.assigneeId(),
                triage.source(), triage.reason());
        return TicketDtos.TicketView.of(tickets.findById(id).orElseThrow());
    }

    public TicketDtos.TicketView updateTriage(UUID id, TicketDtos.UpdateTriageRequest request) {
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        String category = ticket.category();
        String priority = ticket.priority();
        UUID assigneeId = ticket.assigneeId();
        if (request.category() != null && !request.category().isBlank()) {
            category = request.category().toUpperCase();
            if (!triageService.isValidCategory(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_category");
            }
        }
        if (request.priority() != null && !request.priority().isBlank()) {
            priority = request.priority().toUpperCase();
            if (!triageService.isValidPriority(priority)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_priority");
            }
        }
        if (request.assigneeId() != null) {
            users.findById(request.assigneeId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignee_not_found"));
            assigneeId = request.assigneeId();
        }
        tickets.updateTriage(id, category, priority, assigneeId, "MANUAL", "人工调整分诊");
        return TicketDtos.TicketView.of(tickets.findById(id).orElseThrow());
    }

    public TicketDtos.TicketView updateStatus(UUID id, String status) {
        Ticket ticket = tickets.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        String normalized = status == null ? "" : status.toUpperCase();
        if (!List.of("OPEN", "PENDING", "CLOSED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_status");
        }
        tickets.updateStatus(id, normalized);
        if ("CLOSED".equals(normalized)) {
            tickets.markResolved(id);
        } else if (ticket.resolvedAt() != null) {
            tickets.clearResolved(id);
        }
        return TicketDtos.TicketView.of(tickets.findById(id).orElseThrow());
    }

    public List<TicketDtos.TicketView> listBreached() {
        return tickets.listBreached(100).stream().map(TicketDtos.TicketView::of).toList();
    }

    public void aiSuggest(UUID ticketId, SseEmitter emitter) {
        TenantScope scope = requireScope();
        aiExecutor.execute(() -> streamSuggestion(scope, ticketId, emitter));
    }

    private void streamSuggestion(TenantScope scope, UUID ticketId, SseEmitter emitter) {
        TenantScope previous = TenantContext.get();
        TenantContext.set(scope);
        try {
            quotaService.assertAllowed(scope.tenantId());
            Ticket ticket = tickets.findById(ticketId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
            String question = buildQuestion(ticket, tickets.listMessages(ticketId));

            TokenBuffer buffer = new TokenBuffer(emitter);
            RagService.RagResult result = ragService.answer(ticketId, question, buffer::accept);
            try {
                buffer.flush();
            } catch (UncheckedIOException | ClientDisconnectedException ignored) {
                // client already gone; the partial text below is still persisted
            }
            int tokens = result.tokens() > 0 ? result.tokens() : Math.max(1, buffer.text().length() / 2);

            String citations = objectMapper.writeValueAsString(result.citedHits().stream()
                    .map(hit -> Map.of(
                            "sectionId", hit.sectionId().toString(),
                            "pageId", hit.pageId().toString(),
                            "title", hit.pageTitle(),
                            "headingPath", hit.headingPath(),
                            "updatedAt", hit.updatedAt().toString(),
                            "score", hit.score()
                    ))
                    .toList());
            if (!buffer.text().isBlank()) {
                TicketMessage saved = tickets.addMessage(
                        scope.tenantId(), ticketId, "AI", null,
                        buffer.text(), citations, tokens, "INTERNAL");
                tickets.touch(ticketId);
                quotaService.recordUsage(scope.tenantId(), "ai_reply", tokens);
                if (result.tokens() > 0) {
                    aiDoneCounter.increment();
                    send(emitter, "done", Map.of(
                            "messageId", saved.id().toString(),
                            "tokens", tokens,
                            "citations", citations,
                            "traceId", result.traceId(),
                            "retrieved", result.allHits().size(),
                            "cited", result.citedHits().size()
                    ));
                } else {
                    aiPartialCounter.increment();
                }
            }
            emitter.complete();
        } catch (ResponseStatusException ex) {
            aiErrorCounter.increment();
            sendQuietly(emitter, "error", Map.of(
                    "message", ex.getReason() == null ? "request_failed" : ex.getReason()));
            emitter.complete();
        } catch (Exception ex) {
            aiErrorCounter.increment();
            log.error("helpdesk_ai_suggest_failed ticketId={}", ticketId, ex);
            sendQuietly(emitter, "error", Map.of("message", "ai_unavailable"));
            emitter.complete();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private String buildQuestion(Ticket ticket, List<TicketMessage> messages) {
        List<TicketMessage> customerMessages = messages.stream()
                .filter(message -> "CUSTOMER".equals(message.authorType()))
                .toList();
        if (customerMessages.isEmpty()) {
            return ticket.subject() + "\n" + ticket.description();
        }
        StringBuilder sb = new StringBuilder(ticket.subject()).append('\n');
        customerMessages.subList(Math.max(0, customerMessages.size() - 3), customerMessages.size())
                .forEach(message -> sb.append(message.content()).append('\n'));
        return sb.toString();
    }

    private TenantScope requireScope() {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        return scope;
    }

    private void send(SseEmitter emitter, String event, Object payload) throws IOException {
        emitter.send(SseEmitter.event()
                .name(event)
                .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
    }

    private void sendQuietly(SseEmitter emitter, String event, Object payload) {
        try {
            send(emitter, event, payload);
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    private final class TokenBuffer {
        private final SseEmitter emitter;
        private final StringBuilder all = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();

        private TokenBuffer(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void accept(String token) {
            all.append(token);
            pending.append(token);
            if (pending.length() >= 12) {
                flush();
            }
        }

        void flush() {
            if (pending.length() == 0) {
                return;
            }
            String text = pending.toString();
            pending.setLength(0);
            try {
                send(emitter, "token", Map.of("text", text));
            } catch (IOException | IllegalStateException ex) {
                throw new ClientDisconnectedException(ex);
            }
        }

        String text() {
            return all.toString();
        }
    }
}
