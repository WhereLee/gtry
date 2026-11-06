package com.wherelee.helpdesk.ticket;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDtos.TicketView create(@Valid @RequestBody TicketDtos.CreateTicketRequest request) {
        return ticketService.create(request.subject(), request.description(), request.priority());
    }

    @GetMapping
    public List<TicketDtos.TicketView> list(@RequestParam(required = false) String status) {
        return ticketService.list(status);
    }

    @GetMapping("/{id}")
    public TicketDtos.TicketDetail get(@PathVariable UUID id) {
        return ticketService.get(id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDtos.MessageView addMessage(
            @PathVariable UUID id,
            @Valid @RequestBody TicketDtos.AddMessageRequest request
    ) {
        return ticketService.addMessage(id, request.content());
    }

    @PatchMapping("/{id}/status")
    public TicketDtos.TicketView updateStatus(@PathVariable UUID id, @RequestParam String status) {
        return ticketService.updateStatus(id, status);
    }

    @PostMapping(value = "/{id}/ai-suggest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiSuggest(@PathVariable UUID id) {
        SseEmitter emitter = new SseEmitter(120_000L);
        ticketService.aiSuggest(id, emitter);
        return emitter;
    }
}
