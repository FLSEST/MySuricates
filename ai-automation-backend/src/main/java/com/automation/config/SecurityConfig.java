package com.automation.config;

import com.automation.security.ApiKeyAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/v1/system/health").permitAll()
                        .requestMatchers("/api/v1/system/info").permitAll()
                        .requestMatchers("/api/v1/system/auth-status").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // WebSocket endpoint
                        .requestMatchers("/ws/**").permitAll()
                        // Public webhook trigger endpoint
                        .requestMatchers("/api/v1/webhook/trigger/**").permitAll()
                        // OAuth2 callback endpoints (public, like webhooks)
                        .requestMatchers("/api/v1/integrations/*/callback").permitAll()
                        // All API endpoints require API key
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .headers(headers -> headers
                        // No external origins: Monaco (runtime, codicon font, workers) is served from
                        // the frontend bundle (monacoSetup.ts). Whitelisting a public CDN such as
                        // cdn.jsdelivr.net would let an HTML injection load any npm package as script.
                        // worker-src allows Monaco's workers.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                "script-src 'self'; " +
                                "worker-src 'self' blob:; " +
                                "connect-src 'self' ws: wss:; " +
                                "style-src 'self' 'unsafe-inline'; " +
                                "font-src 'self' data:; " +
                                "img-src 'self' data:"))
                        .frameOptions(fo -> fo.deny()))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
