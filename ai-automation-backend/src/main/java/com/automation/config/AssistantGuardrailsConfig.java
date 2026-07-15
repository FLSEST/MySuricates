package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Guardrails for assistant-initiated platform mutations (TODO 9f022277).
 * Mutated at runtime by ExecutionSettingsService (key: assistant-max-actions-per-hour).
 *
 * The confirm-gate (PendingToolRegistry) already ensures a human approves every
 * mutation; this config bounds the blast radius even with a click-happy user.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.assistant.guardrails")
@Getter
@Setter
public class AssistantGuardrailsConfig {
    /** Maximum confirmed mutating tool executions per rolling hour. */
    private int maxActionsPerHour = 30;
}
