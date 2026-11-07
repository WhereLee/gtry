package com.wherelee.helpdesk.portal;

import com.wherelee.helpdesk.config.HelpdeskProperties;
import com.wherelee.helpdesk.attachment.AttachmentService;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.ticket.Ticket;
import com.wherelee.helpdesk.ticket.TicketDtos;
import com.wherelee.helpdesk.ticket.TicketMessage;
import com.wherelee.helpdesk.ticket.TicketRepository;
import com.wherelee.helpdesk.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalService.class);

    private final PortalRepository portalRepository;
    private final PortalRateLimiter rateLimiter;
    private final TicketService ticketService;
    private final TicketRepository tickets;
    private final AttachmentService attachments;
    private final HelpdeskProperties.PublicPortal config;
    private final TransactionTemplate transactionTemplate;

    public PortalService(
            PortalRepository portalRepository,
            PortalRateLimiter rateLimiter,
            TicketService ticketService,
            TicketRepository tickets,
            AttachmentService attachments,
            HelpdeskProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.portalRepository = portalRepository;
        this.rateLimiter = rateLimiter;
        this.ticketService = ticketService;
        this.tickets = tickets;
        this.attachments = attachments;
        this.config = properties.getPublicPortal();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PortalDtos.CreateTicketResponse create(
            String channelKey, PortalDtos.CreateTicketRequest request) {
        if (channelKey == null || channelKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_channel");
        }
        if (request.website() != null && !request.website().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        String keyHash = TokenHasher.sha256Hex(channelKey);
        PortalRepository.ChannelRef channel = portalRepository.resolveChannel(keyHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_channel"));
        rateLimiter.assertAllowed("channel:" + channel.channelId());

        String name = blankToNull(request.name());
        String email = blankToNull(request.email());
        PortalDtos.CreateTicketResponse response = TenantContext.callWith(
                TenantScope.tenant(channel.tenantId()), () -> transactionTemplate.execute(status -> {
                    TicketDtos.TicketView ticket = ticketService.create(
                            request.subject().trim(), request.description().trim(), null, name, email);
                    String token = TokenHasher.newToken();
                    Instant expiresAt = Instant.now().plus(config.getTokenTtlDays(), ChronoUnit.DAYS);
                    tickets.setCustomerToken(ticket.id(), TokenHasher.sha256Hex(token), expiresAt);
                    return new PortalDtos.CreateTicketResponse(
                            ticket.id(), token, expiresAt, ticket.status());
                }));
        if (response == null) {
            throw new IllegalStateException("portal ticket transaction returned no result");
        }
        log.info("helpdesk_portal_ticket_created tenant={} ticket={}",
                channel.tenantId(), response.ticketId());
        return response;
    }

    public PortalDtos.PublicTicketView get(UUID ticketId, String token) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        return TenantContext.callWith(TenantScope.tenant(ref.tenantId()), () -> {
            Ticket ticket = tickets.findById(ref.ticketId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
            return view(ticket, tickets.listPublicMessages(ref.ticketId()));
        });
    }

    public PortalDtos.PublicTicketView reply(UUID ticketId, String token, String content) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        return TenantContext.callWith(TenantScope.tenant(ref.tenantId()),
                () -> transactionTemplate.execute(status -> {
                    Ticket ticket = tickets.findById(ref.ticketId())
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "ticket_not_found"));
                    tickets.addMessage(
                            ref.tenantId(), ref.ticketId(), "CUSTOMER", null,
                            content.trim(), "[]", 0, "PUBLIC");
                    if (!"OPEN".equals(ticket.status())) {
                        tickets.updateStatus(ref.ticketId(), "OPEN");
                    } else {
                        tickets.touch(ref.ticketId());
                    }
                    Ticket refreshed = tickets.findById(ref.ticketId()).orElseThrow();
                    return view(refreshed, tickets.listPublicMessages(ref.ticketId()));
                }));
    }

    public void csat(UUID ticketId, String token, int rating, String comment) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        TenantContext.runWith(TenantScope.tenant(ref.tenantId()),
                () -> transactionTemplate.executeWithoutResult(status -> {
                    Ticket ticket = tickets.findById(ref.ticketId())
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "ticket_not_found"));
                    if (!"CLOSED".equals(ticket.status())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "ticket_not_closed");
                    }
                    tickets.updateCsat(ref.ticketId(), rating, blankToNull(comment));
                }));
    }

    public AttachmentService.AttachmentView uploadAttachment(
            UUID ticketId, String token, MultipartFile file) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        return TenantContext.callWith(TenantScope.tenant(ref.tenantId()),
                () -> attachments.store(ref.tenantId(), ref.ticketId(), file, "PUBLIC"));
    }

    public List<AttachmentService.AttachmentView> listAttachments(UUID ticketId, String token) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        return TenantContext.callWith(TenantScope.tenant(ref.tenantId()),
                () -> attachments.list(ref.ticketId(), true));
    }

    public AttachmentService.Download downloadAttachment(
            UUID ticketId, UUID attachmentId, String token) {
        PortalRepository.TokenRef ref = resolveToken(ticketId, token);
        rateLimiter.assertAllowed("token:" + tokenHashPrefix(token));
        return TenantContext.callWith(TenantScope.tenant(ref.tenantId()), () -> {
            AttachmentService.Download download = attachments.load(attachmentId);
            if (!download.attachment().ticketId().equals(ref.ticketId())
                    || !"PUBLIC".equals(download.attachment().visibility())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "attachment_not_found");
            }
            return download;
        });
    }

    private PortalRepository.TokenRef resolveToken(UUID ticketId, String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_ticket_token");
        }
        PortalRepository.TokenRef ref = portalRepository.resolveToken(TokenHasher.sha256Hex(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found"));
        if (!ref.ticketId().equals(ticketId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ticket_not_found");
        }
        return ref;
    }

    private PortalDtos.PublicTicketView view(Ticket ticket, List<TicketMessage> messages) {
        return new PortalDtos.PublicTicketView(
                ticket.id(), ticket.subject(), ticket.status(), ticket.category(),
                ticket.createdAt(), ticket.updatedAt(), ticket.csatRating(), ticket.csatComment(),
                messages.stream()
                        .map(message -> new PortalDtos.PublicMessageView(
                                message.authorType(), message.content(), message.createdAt()))
                        .toList()
        );
    }

    private String tokenHashPrefix(String token) {
        String hash = TokenHasher.sha256Hex(token);
        return hash.substring(0, 12);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
