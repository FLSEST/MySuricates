package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.summarization")
@Getter
@Setter
public class SummarizationConfig {

    /**
     * Base directory for summarization working dirs.
     * Each provider gets a subdirectory (claude/, codex/).
     */
    private String baseDir = System.getProperty("user.home") + "/.autotrigger/summarization";

    /**
     * Timeout in seconds for a single summarization CLI call.
     */
    private int timeoutSeconds = 120;
}
