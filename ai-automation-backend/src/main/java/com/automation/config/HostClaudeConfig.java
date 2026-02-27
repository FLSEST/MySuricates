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
    private int maxConcurrentCalls = 3;
}
