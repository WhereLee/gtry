package com.wherelee.helpdesk.tenant;

import java.util.UUID;

public record TenantScope(
        UUID tenantId,
        UUID userId,
        String email,
        String role,
        boolean platformAdmin
) {
    public static TenantScope tenant(UUID tenantId) {
        return new TenantScope(tenantId, null, null, "SYSTEM", false);
    }

    public static TenantScope platform(UUID tenantId) {
        return new TenantScope(tenantId, null, null, "PLATFORM_ADMIN", true);
    }
}
