package com.wherelee.helpdesk.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank String tenantSlug,
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record UserView(
            UUID id,
            String email,
            String displayName,
            String role,
            UUID tenantId,
            String tenantSlug
    ) {
    }

    public record LoginResponse(String token, UserView user) {
    }
}
