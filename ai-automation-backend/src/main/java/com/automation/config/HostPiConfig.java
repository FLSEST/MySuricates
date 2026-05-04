package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.host-pi")
@Getter
@Setter
public class HostPiConfig {

    private int timeoutSeconds = 300;

    private Integer maxTokens;

    private long interCallDelayMs = 0;

    private int maxConcurrentCalls = 5;

    private int reservedForInteractive = 1;

    /**
     * Backend-Provider for pi when the workflow's model field has no "<backend>/" prefix.
     * Pi internally routes to anthropic / openai / google / xai / groq / cerebras / openrouter
     * or any custom provider configured in ~/.pi/agent/models.json (e.g. ollama, lmstudio, vllm).
     */
    private String defaultBackendProvider = "anthropic";

    /**
     * Default thinking level used by reason() when no maxTurns hint maps to one.
     * Valid values: off, minimal, low, medium, high, xhigh.
     */
    private String defaultThinkingLevel = "medium";

    /**
     * Directory containing pi's runtime config (auth.json, settings.json, models.json).
     * Default mirrors pi's own convention: $HOME/.pi/agent.
     */
    private String configDir = System.getProperty("user.home") + "/.pi/agent";
}
