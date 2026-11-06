package com.wherelee.helpdesk.user;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserView> list() {
        requireTenantAdmin();
        return users.listByTenant(TenantContext.get().tenantId()).stream()
                .map(UserView::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        requireTenantAdmin();
        TenantScope scope = TenantContext.get();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user_exists");
        }
        String role = normalizeRole(request.role());
        AppUser user = users.create(
                scope.tenantId(), email, passwordEncoder.encode(request.password()),
                request.displayName().trim(), role);
        return UserView.of(user);
    }

    @PatchMapping("/{id}")
    public UserView update(@PathVariable UUID id, @RequestBody UpdateUserRequest request) {
        requireTenantAdmin();
        TenantScope scope = TenantContext.get();
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found"));
        String role = user.role();
        String status = user.status();
        if (request.role() != null && !request.role().isBlank()) {
            role = normalizeRole(request.role());
        }
        if (request.status() != null && !request.status().isBlank()) {
            status = request.status().toUpperCase(Locale.ROOT);
            if (!List.of("ACTIVE", "DISABLED").contains(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_user_status");
            }
            if (id.equals(scope.userId()) && "DISABLED".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_disable_self");
            }
        }
        boolean losingLastAdmin = "TENANT_ADMIN".equals(user.role())
                && (!"TENANT_ADMIN".equals(role) || !"ACTIVE".equals(status))
                && users.countActiveTenantAdmins(scope.tenantId()) <= 1;
        if (losingLastAdmin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "last_tenant_admin");
        }
        users.updateRoleStatus(id, role, status);
        return UserView.of(users.findById(id).orElseThrow());
    }

    @GetMapping("/agents")
    public List<AgentView> agents() {
        return users.listAssignable(TenantContext.get().tenantId()).stream()
                .map(user -> new AgentView(
                        user.id(), user.displayName(), user.email(), user.role()))
                .toList();
    }

    public record AgentView(UUID id, String displayName, String email, String role) {
    }

    public record UserView(UUID id, String email, String displayName, String role, String status) {
        static UserView of(AppUser user) {
            return new UserView(
                    user.id(), user.email(), user.displayName(), user.role(), user.status());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank String role,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {
    }

    public record UpdateUserRequest(String role, String status) {
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.toUpperCase(Locale.ROOT);
        if (!List.of("AGENT", "TENANT_ADMIN").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_role");
        }
        return normalized;
    }

    private void requireTenantAdmin() {
        TenantScope scope = TenantContext.get();
        if (scope == null || (!scope.platformAdmin() && !"TENANT_ADMIN".equals(scope.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "tenant_admin_required");
        }
    }
}
