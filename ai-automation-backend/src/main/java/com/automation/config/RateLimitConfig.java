package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.security.rate-limit")
@Getter
@Setter
public class RateLimitConfig {
    private boolean enabled = true;
    private int requestsPerMinute = 120;

    /**
     * Sekunden, die der Browser-Client nach einem 429 <em>alle</em> übrigen Pfade kurz sperrt
     * (gedeckelt durch {@code Retry-After}). Geht über {@code /api/v1/system/info} an den Client;
     * der Default entspricht dessen eingebauter Konstante {@code GLOBAL_BACKOFF_SECONDS}.
     */
    private int clientGlobalBackoffSeconds = 2;

    /**
     * Sekunden, die der Client annimmt, wenn eine 429-Antwort keinen brauchbaren
     * {@code Retry-After}-Header trägt. Entspricht dessen {@code DEFAULT_RETRY_AFTER_SECONDS}.
     */
    private int clientDefaultRetryAfterSeconds = 10;
}
