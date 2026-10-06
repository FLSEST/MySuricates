package com.automation.config;

import com.automation.security.ApiKeyAuthFilter;
import com.automation.security.PublicPaths;
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
                // Which paths are reachable without a key is defined solely in PublicPaths — the
                // ApiKeyAuthFilter reads the same matchers to log missing keys.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicPaths.publicMatchers()).permitAll()
                        .requestMatchers(PublicPaths.keyRequiredMatchers()).authenticated()
                        // ausgeliefertes Frontend
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
