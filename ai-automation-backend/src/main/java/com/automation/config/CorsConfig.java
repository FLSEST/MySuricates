package com.automation.config;

import com.automation.security.AllowedOrigins;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * One CORS rule set for every path. Which origins pass is decided by {@link AllowedOrigins}: the
 * configured list plus, with remote-access on, this instance's own Tailscale addresses. No
 * credentials (a8056256).
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final AllowedOrigins allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", allowedOrigins.corsConfiguration());
        return new CorsFilter(source);
    }
}
