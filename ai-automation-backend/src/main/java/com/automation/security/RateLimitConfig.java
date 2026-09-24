package com.automation.security;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "automation.security.rate-limit")
@Validated
@Getter
@Setter
public class RateLimitConfig {
    private boolean enabled = true;
    private int requestsPerMinute = 120;

    /**
     * Sekunden, die der Browser-Client nach einem 429 <em>alle</em> übrigen Pfade kurz sperrt
     * (gedeckelt durch {@code Retry-After}). Geht über {@code /api/v1/system/info} an den Client;
     * der Default entspricht dessen eingebauter Konstante {@code GLOBAL_BACKOFF_SECONDS}.
     * {@code @Min(1)}, damit ein negativer Wert beim Start auffällt statt still im Client zu
     * landen (der ihn dann verwerfen und wieder raten müsste).
     */
    @Min(1)
    private int clientGlobalBackoffSeconds = 2;

    /**
     * Sekunden, die der Client annimmt, wenn eine 429-Antwort keinen brauchbaren
     * {@code Retry-After}-Header trägt. Entspricht dessen {@code DEFAULT_RETRY_AFTER_SECONDS}.
     */
    @Min(1)
    private int clientDefaultRetryAfterSeconds = 10;
}
