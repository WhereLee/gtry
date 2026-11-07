package com.wherelee.helpdesk.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiConfigController {

    private final TenantAiConfigService configService;

    public AiConfigController(TenantAiConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/config")
    public AiConfigDtos.AiConfigView get() {
        return AiConfigDtos.AiConfigView.of(configService.current());
    }

    @PutMapping("/config")
    public AiConfigDtos.AiConfigView update(
            @Valid @RequestBody AiConfigDtos.UpdateAiConfigRequest request
    ) {
        return AiConfigDtos.AiConfigView.of(configService.update(request));
    }
}
