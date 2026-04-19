package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.host-claude")
@Getter
@Setter
public class HostClaudeConfig {

    /**
     * Timeout in seconds for a single claude -p call on the host.
     */
    private int timeoutSeconds = 300;

    /**
     * Optional max-tokens flag passed to claude CLI.
     */
    private Integer maxTokens;

    /**
     * Minimum delay in milliseconds between consecutive Claude CLI calls (0 = disabled).
     */
    private long interCallDelayMs = 0;

    /**
     * Maximum number of concurrent Claude CLI processes.
     */
    private int maxConcurrentCalls = 5;

    /**
     * Number of permits reserved for interactive (priority) calls like assistant chat.
     * Background calls (workflows, suggestions) are limited to maxConcurrentCalls - reservedForInteractive.
     */
    private int reservedForInteractive = 1;

    /**
     * Timeout in seconds for a single assistant chat CLI call.
     * Applies per CLI call inside {@code AssistantService.chat()} — with fetch loops, the worst-case
     * wait is this value multiplied by the number of fetch rounds. Kept separate from
     * {@link #timeoutSeconds} so workflow/suggestion calls can have a different budget.
     */
    private int assistantChatTimeoutSeconds = 240;
}
