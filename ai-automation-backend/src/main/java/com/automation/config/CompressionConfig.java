package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Limits of the compression blocks. Mutated at runtime by ExecutionSettingsService
 * (key compress-ai-max-input-chars).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.compression")
@Getter
@Setter
public class CompressionConfig {
    /** compress-ai: largest source text in chars sent to the model; above it the step fails without an AI call. */
    private int maxAiInputChars = 60_000;
}
