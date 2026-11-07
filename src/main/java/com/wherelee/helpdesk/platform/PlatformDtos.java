package com.wherelee.helpdesk.platform;

import com.wherelee.helpdesk.tenant.TenantRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class PlatformDtos {

    private PlatformDtos() {
    }

    public record CreateTenantRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9-]{3,64}") String slug,
            @NotBlank @Size(max = 128) String name,
            String plan,
            Long monthlyTokenBudget,
            @NotBlank @Email @Size(max = 255) String adminEmail,
            @NotBlank @Size(min = 8, max = 128) String adminPassword,
            @Size(max = 128) String adminDisplayName
    ) {
    }

    public record TenantSummary(
            UUID id,
            String slug,
            String name,
            String plan,
            String status,
            long monthlyTokenBudget,
            long userCount,
            long ticketCount,
            long tokensThisMonth,
            long costMicrosThisMonth,
            Instant createdAt,
            Instant deletedAt
    ) {
        public static TenantSummary of(TenantRepository.TenantStats stats) {
            return new TenantSummary(
                    stats.id(), stats.slug(), stats.name(), stats.plan(), stats.status(),
                    stats.monthlyTokenBudget(), stats.userCount(), stats.ticketCount(),
                    stats.tokensThisMonth(), stats.costMicrosThisMonth(),
                    stats.createdAt(), stats.deletedAt()
            );
        }
    }
}
