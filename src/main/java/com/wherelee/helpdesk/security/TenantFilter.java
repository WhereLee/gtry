package com.wherelee.helpdesk.security;

import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenRevocationService revocationService;
    private final TenantRepository tenantRepository;

    public TenantFilter(
            JwtService jwtService,
            TokenRevocationService revocationService,
            TenantRepository tenantRepository
    ) {
        this.jwtService = jwtService;
        this.revocationService = revocationService;
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean publicRequest = !path.startsWith("/api/")
                || path.equals("/api/auth/login")
                || path.startsWith("/api/public/");
        if (publicRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "missing_token");
            return;
        }
        try {
            JwtService.ParsedToken parsed = jwtService.parseDetails(header.substring(7));
            if (revocationService.isRevoked(parsed.jti())) {
                unauthorized(response, "token_revoked");
                return;
            }
            if (!parsed.scope().platformAdmin()) {
                String tenantStatus = tenantRepository.findById(parsed.scope().tenantId())
                        .map(Tenant::status)
                        .orElse("DELETED");
                if (!"ACTIVE".equals(tenantStatus)) {
                    unauthorized(response, "tenant_inactive");
                    return;
                }
            }
            TenantContext.set(parsed.scope());
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            unauthorized(response, "invalid_token");
        } finally {
            TenantContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
