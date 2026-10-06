package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Startup values of the AI CLI limits that have no config class of their own: the overall limit across
 * all providers and the Codex limit. Claude and pi keep theirs in {@link HostClaudeConfig} and
 * {@link HostPiConfig}. All of them can be changed at runtime in the settings (TODO bd5df8d7).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.ai-cli")
@Getter
@Setter
public class AiCliConfig {

    /** AI CLI processes of all providers together. */
    private int maxConcurrentCalls = 8;

    /** Of those, kept free for interactive calls such as the assistant chat. */
    private int reservedForInteractive = 1;

    private int codexMaxConcurrentCalls = 5;

    private int codexReservedForInteractive = 1;

    /** Minimum distance in milliseconds between two Codex calls (0 = none). */
    private long codexInterCallDelayMs = 0;
}
