package com.automation.service.system.settings;

import com.automation.config.AssistantGuardrailsConfig;
import com.automation.config.RateLimitConfig;
import com.automation.service.cli.ContainerShellService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Zugriffs- und Mengengrenzen: API-Rate-Limit, Extra-Mounts, Aktionen des Assistenten. */
@Component
@Order(2)
public class AccessLimitSettings implements RuntimeSettingsGroup {

    private final RateLimitConfig rateLimitConfig;
    private final ContainerShellService containerShellService;
    private final AssistantGuardrailsConfig assistantGuardrailsConfig;

    public AccessLimitSettings(RateLimitConfig rateLimitConfig, ContainerShellService containerShellService,
                               AssistantGuardrailsConfig assistantGuardrailsConfig) {
        this.rateLimitConfig = rateLimitConfig;
        this.containerShellService = containerShellService;
        this.assistantGuardrailsConfig = assistantGuardrailsConfig;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("rate-limit-enabled", v -> rateLimitConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("rate-limit-requests-per-minute", v -> rateLimitConfig.setRequestsPerMinute(Integer.parseInt(v)));
        m.put("allow-extra-mounts", v -> containerShellService.setAllowExtraMounts(Integer.parseInt(v) == 1));
        m.put("assistant-max-actions-per-hour", v -> assistantGuardrailsConfig.setMaxActionsPerHour(Integer.parseInt(v)));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        out.put("rateLimitEnabled", rateLimitConfig.isEnabled() ? 1 : 0);
        out.put("rateLimitRequestsPerMinute", rateLimitConfig.getRequestsPerMinute());
        out.put("allowExtraMounts", containerShellService.isAllowExtraMounts() ? 1 : 0);
        out.put("assistantMaxActionsPerHour", assistantGuardrailsConfig.getMaxActionsPerHour());
    }
}
