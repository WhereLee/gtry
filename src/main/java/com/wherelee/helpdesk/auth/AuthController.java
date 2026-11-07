package com.wherelee.helpdesk.auth;

import com.wherelee.helpdesk.security.JwtService;
import com.wherelee.helpdesk.security.LoginRateLimiter;
import com.wherelee.helpdesk.security.TokenRevocationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtService jwtService;
    private final TokenRevocationService revocationService;

    public AuthController(
            AuthService authService,
            LoginRateLimiter loginRateLimiter,
            JwtService jwtService,
            TokenRevocationService revocationService
    ) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtService = jwtService;
        this.revocationService = revocationService;
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(
            @Valid @RequestBody AuthDtos.LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.assertAllowed(
                servletRequest.getRemoteAddr() + ":" + request.tenantSlug() + ":" + request.email());
        return authService.login(request.tenantSlug(), request.email(), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        String token = authorization.substring(7);
        JwtService.ParsedToken parsed = jwtService.parseDetails(token);
        revocationService.revoke(parsed.jti(), parsed.expiresAt());
    }

    @GetMapping("/me")
    public AuthDtos.UserView me() {
        return authService.currentUser();
    }
}
