package com.automation.security;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ErrorCode;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    /** Read-only token of one run (TODO cfb5d56b), see {@link RunTokenService} and {@link RunTokenPaths}. */
    static final String RUN_TOKEN_HEADER = "X-Run-Token";
    static final String ROLE_API_USER = "ROLE_API_USER";
    static final String ROLE_RUN_TOKEN = "ROLE_RUN_TOKEN";

    private final ApiKeyService apiKeyService;
    private final ActivityLogService activityLogService;
    /** Absent in web slices without the workflow engine: then every run token counts as unknown. */
    private final Supplier<RunTokenService> runTokenService;

    @Autowired
    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ActivityLogService activityLogService,
                            ObjectProvider<RunTokenService> runTokenService) {
        this.apiKeyService = apiKeyService;
        this.activityLogService = activityLogService;
        this.runTokenService = runTokenService::getIfAvailable;
    }

    ApiKeyAuthFilter(ApiKeyService apiKeyService, ActivityLogService activityLogService,
                     RunTokenService runTokenService) {
        this.apiKeyService = apiKeyService;
        this.activityLogService = activityLogService;
        this.runTokenService = () -> runTokenService;
    }

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

        if (apiKeyService.matchesEffectiveApiKey(apiKey)) {
            setAuthenticated("api-key-user");
            filterChain.doFilter(request, response);
        } else if (request.getHeader(RUN_TOKEN_HEADER) != null) {
            authenticateRunToken(request, response, filterChain);
        } else {
            if (apiKey != null) {
                // Key was provided but is wrong — log as security event
                activityLogService.warn(LogCategory.SECURITY,
                        "Authentication failed: invalid API key for " + request.getRequestURI(),
                        "ApiKeyAuthFilter", null);
            } else if (PublicPaths.requiresKey(request)) {
                activityLogService.warn(LogCategory.SECURITY,
                        "Authentication failed: no API key provided for " + request.getRequestURI(),
                        "ApiKeyAuthFilter", null);
            }
            // Let Spring Security handle unauthenticated request
            // (will return 403 for protected endpoints, pass through for public ones)
            filterChain.doFilter(request, response);
        }
    }

    /**
     * A run token may only read the allowlist ({@link RunTokenPaths}); everything else is refused here and
     * not left to the authorization rules, so the answer says why. {@code SecurityConfig} checks the role on
     * top of that.
     */
    private void authenticateRunToken(HttpServletRequest request, HttpServletResponse response,
                                      FilterChain filterChain) throws IOException, ServletException {
        String token = request.getHeader(RUN_TOKEN_HEADER);
        RunTokenService tokens = runTokenService.get();
        Optional<String> runId = tokens == null ? Optional.empty() : tokens.resolve(token);
        if (runId.isEmpty()) {
            activityLogService.warn(LogCategory.SECURITY,
                    "Authentication failed: unknown or expired run token for " + request.getRequestURI(),
                    "ApiKeyAuthFilter", null);
            refuse(response, ErrorCode.RUN_TOKEN_INVALID);
            return;
        }
        if (!RunTokenPaths.allows(request)) {
            activityLogService.warn(LogCategory.SECURITY,
                    "Run token refused: " + request.getMethod() + " " + request.getRequestURI()
                            + " is not readable with a run token",
                    "ApiKeyAuthFilter", runId.get());
            refuse(response, ErrorCode.RUN_TOKEN_SCOPE);
            return;
        }
        setAuthenticated("run:" + runId.get(), ROLE_RUN_TOKEN);
        tokens.recordRead(token, request.getRequestURI());
        filterChain.doFilter(request, response);
    }

    /** Written by hand like the other filters: the fields of ErrorResponse. */
    private static void refuse(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code.name() + "\",\"error\":\"" + code.getDefaultMessage()
                + "\",\"timestamp\":\"" + Instant.now() + "\"}\n");
    }

    private void setAuthenticated(String principal) {
        setAuthenticated(principal, ROLE_API_USER);
    }

    private void setAuthenticated(String principal, String role) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
