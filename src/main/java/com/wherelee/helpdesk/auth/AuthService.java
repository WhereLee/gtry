package com.wherelee.helpdesk.auth;

import com.wherelee.helpdesk.security.JwtService;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import com.wherelee.helpdesk.user.AppUser;
import com.wherelee.helpdesk.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            TenantRepository tenants,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthDtos.LoginResponse login(String tenantSlug, String email, String password) {
        Tenant tenant = tenants.findBySlug(tenantSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials"));
        if (!"ACTIVE".equals(tenant.status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_inactive");
        }
        AppUser user = TenantContext.callWith(
                TenantScope.tenant(tenant.id()),
                () -> users.findByEmail(email).orElse(null)
        );
        if (user == null || !"ACTIVE".equals(user.status())
                || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        String token = jwtService.issue(tenant.id(), user.id(), user.email(), user.role());
        return new AuthDtos.LoginResponse(token, view(user, tenant.slug()));
    }

    public AuthDtos.UserView currentUser() {
        TenantScope scope = TenantContext.get();
        if (scope == null || scope.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_token");
        }
        AppUser user = users.findById(scope.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_not_found"));
        Tenant tenant = tenants.findById(user.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_not_found"));
        return view(user, tenant.slug());
    }

    private AuthDtos.UserView view(AppUser user, String tenantSlug) {
        return new AuthDtos.UserView(
                user.id(), user.email(), user.displayName(), user.role(), user.tenantId(), tenantSlug
        );
    }
}
