package com.wherelee.helpdesk.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AiConfigDtos {

    private AiConfigDtos() {
    }

    public record AiConfigView(
            String model,
            int topK,
            String extraInstructions,
            boolean enabled
    ) {
        public static AiConfigView of(TenantAiConfigRepository.Config config) {
            return new AiConfigView(
                    config.model(), config.topK(), config.extraInstructions(), config.enabled());
        }
    }

    public record UpdateAiConfigRequest(
            @NotBlank @Size(max = 64) String model,
            @Min(1) @Max(10) int topK,
            @Size(max = 4000) String extraInstructions,
            boolean enabled
    ) {
    }
}
