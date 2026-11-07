package com.wherelee.helpdesk.notification;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.user.AppUser;
import com.wherelee.helpdesk.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final UserRepository users;

    public NotificationService(NotificationRepository repository, UserRepository users) {
        this.repository = repository;
        this.users = users;
    }

    public void notifyTenantAdmins(
            UUID tenantId, UUID actorId, String type, String title, String body,
            String targetType, String targetId
    ) {
        for (AppUser user : users.listByTenant(tenantId)) {
            if ("TENANT_ADMIN".equals(user.role())
                    && "ACTIVE".equals(user.status())
                    && !user.id().equals(actorId)) {
                repository.insert(tenantId, user.id(), type, title, body, targetType, targetId);
            }
        }
    }

    public void notifyUser(
            UUID tenantId, UUID userId, UUID actorId, String type, String title, String body,
            String targetType, String targetId
    ) {
        if (userId == null || userId.equals(actorId)) {
            return;
        }
        repository.insert(tenantId, userId, type, title, body, targetType, targetId);
    }

    public List<NotificationView> list(boolean unreadOnly, int limit) {
        TenantScope scope = requireUser();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return repository.list(scope.userId(), unreadOnly, safeLimit).stream()
                .map(NotificationView::of)
                .toList();
    }

    public long unreadCount() {
        return repository.unreadCount(requireUser().userId());
    }

    public void markRead(UUID id) {
        repository.markRead(requireUser().userId(), id);
    }

    public void markAllRead() {
        repository.markAllRead(requireUser().userId());
    }

    private TenantScope requireUser() {
        TenantScope scope = TenantContext.get();
        if (scope == null || scope.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        return scope;
    }

    public record NotificationView(
            UUID id,
            String type,
            String title,
            String body,
            String targetType,
            String targetId,
            Instant readAt,
            Instant createdAt
    ) {
        static NotificationView of(Notification notification) {
            return new NotificationView(
                    notification.id(), notification.type(), notification.title(), notification.body(),
                    notification.targetType(), notification.targetId(),
                    notification.readAt(), notification.createdAt());
        }
    }
}
