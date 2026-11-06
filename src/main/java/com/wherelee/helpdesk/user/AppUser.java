package com.wherelee.helpdesk.user;

import java.util.UUID;

public record AppUser(
        UUID id,
        UUID tenantId,
        String email,
        String passwordHash,
        String displayName,
        String role,
        String status
) {
}
