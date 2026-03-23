package com.automation.security;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ActivityLogService activityLogService;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String effectiveApiKey = apiKeyService.getEffectiveApiKey();

        // If security is disabled or no API key available, authenticate all requests
        if (!securityEnabled || effectiveApiKey.isBlank()) {
            setAuthenticated("anonymous");
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && constantTimeEquals(apiKey, effectiveApiKey)) {
            setAuthenticated("api-key-user");
            filterChain.doFilter(request, response);
        } else {
            if (apiKey != null) {
                // Key was provided but is wrong — log as security event
                activityLogService.warn(LogCategory.SECURITY,
                        "Authentication failed: invalid API key for " + request.getRequestURI(),
                        "ApiKeyAuthFilter", null);
            }
            // Let Spring Security handle unauthenticated request
            // (will return 403 for protected endpoints, pass through for public ones)
            filterChain.doFilter(request, response);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void setAuthenticated(String principal) {
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_API_USER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
