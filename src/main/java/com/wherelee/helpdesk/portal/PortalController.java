package com.wherelee.helpdesk.portal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/public/tickets")
public class PortalController {

    private final PortalService portalService;

    public PortalController(PortalService portalService) {
        this.portalService = portalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortalDtos.CreateTicketResponse create(
            @RequestHeader("X-Channel-Key") String channelKey,
            @Valid @RequestBody PortalDtos.CreateTicketRequest request
    ) {
        return portalService.create(channelKey, request);
    }

    @GetMapping("/{id}")
    public PortalDtos.PublicTicketView get(
            @PathVariable UUID id,
            @RequestHeader("X-Ticket-Token") String token
    ) {
        return portalService.get(id, token);
    }

    @PostMapping("/{id}/messages")
    public PortalDtos.PublicTicketView reply(
            @PathVariable UUID id,
            @RequestHeader("X-Ticket-Token") String token,
            @Valid @RequestBody PortalDtos.ReplyRequest request
    ) {
        return portalService.reply(id, token, request.content());
    }

    @PostMapping("/{id}/csat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csat(
            @PathVariable UUID id,
            @RequestHeader("X-Ticket-Token") String token,
            @Valid @RequestBody PortalDtos.CsatRequest request
    ) {
        portalService.csat(id, token, request.rating(), request.comment());
    }
}
