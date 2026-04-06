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
}
