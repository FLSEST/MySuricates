package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Threshold of the keyword pre-filter of the TODO similarity search. Changeable at runtime via
 * {@code ExecutionSettingsService} (key
 * {@code todo-similarity-keyword-threshold}).
 *
 * <p>The value is the only lever against merging too aggressively: too low, and
 * loop triggers get swallowed into a similar open TODO; too high, and the same thing
 * arises several times. That is why it belongs in the settings and not in the code (TODO 380818b1).
 */
@Configuration
@ConfigurationProperties(prefix = "automation.todo.similarity")
@Getter
@Setter
public class TodoSimilarityConfig {
    /** Jaccard minimum from which an open TODO goes into the AI check as a candidate. */
    private double keywordThreshold = 0.2;
}
