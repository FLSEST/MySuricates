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
     * Seconds for which the browser client briefly blocks <em>all</em> other paths after a 429
     * (capped by {@code Retry-After}). Goes to the client via {@code /api/v1/system/info};
     * the default matches its built-in constant {@code GLOBAL_BACKOFF_SECONDS}.
     * {@code @Min(1)}, so that a negative value is noticed at startup instead of silently ending up in the
     * client (which would then have to discard it and guess again).
     */
    @Min(1)
    private int clientGlobalBackoffSeconds = 2;

    /**
     * Seconds the client assumes when a 429 response carries no usable
     * {@code Retry-After} header. Matches its {@code DEFAULT_RETRY_AFTER_SECONDS}.
     */
    @Min(1)
    private int clientDefaultRetryAfterSeconds = 10;
}
