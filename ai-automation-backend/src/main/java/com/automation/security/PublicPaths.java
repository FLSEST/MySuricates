package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The one list of which paths are reachable without an API key. {@code SecurityConfig} builds the
 * authorization rules from it, {@link ApiKeyAuthFilter} uses it to decide whether a missing key is worth a
 * SECURITY warning — both evaluate the same {@link RequestMatcher} objects so that rule
 * and log do not drift apart again (previously the config treated the OAuth callback as
 * public, the filter as protected).
 *
 * <p>Order of evaluation, as in the filter chain: first {@link #EXACT} and {@link #PATTERNS}
 * (open), then {@link #PROTECTED} (key required), everything else is open (the served frontend).
 *
 * <p>Matching uses {@link PathPatternRequestMatcher} (Spring {@code PathPattern} on the path without
 * context path): exact paths match only themselves — no trailing slash, no
 * subpath, case matters —, {@code *} stands for exactly one segment, {@code /**} for
 * zero or more segments.
 */
public final class PublicPaths {

    private PublicPaths() {}

    /**
     * Key-free probes: exact paths without wildcards. The liveness and heap probes that
     * workflows may call without a key ({@code SelfAccessRules.KEY_FREE_PROBES},
     * {@code HYGIENE_POOL_API_ALLOWED}) must be listed here.
     */
    public static final List<String> EXACT = List.of(
            "/api/v1/system/health",
            "/api/v1/system/info",
            "/api/v1/system/auth-status",
            // Exactly three heap numbers for the performance audit; the metrics surface under
            // /actuator/metrics stays behind the key.
            "/api/v1/system/jvm-memory",
            "/actuator/health");

    /**
     * Public patterns: the WebSocket handshake (checks its ticket itself), the webhook intake
     * (checks token and signature itself) and the OAuth callbacks of the integrations (the provider calls
     * back without a key, the {@code state} is checked).
     */
    public static final List<String> PATTERNS = List.of(
            "/ws/**",
            "/api/v1/webhook/trigger/**",
            "/api/v1/integrations/*/callback");

    /** Areas in which everything outside {@link #EXACT} and {@link #PATTERNS} needs the key. */
    public static final List<String> PROTECTED = List.of(
            "/actuator/**",
            "/api/**");

    private static final RequestMatcher[] PUBLIC =
            matchers(Stream.concat(EXACT.stream(), PATTERNS.stream()).toList());
    private static final RequestMatcher[] KEY_REQUIRED = matchers(PROTECTED);

    /** Reachable without a key — evaluated in the filter chain before {@link #keyRequiredMatchers()}. */
    public static RequestMatcher[] publicMatchers() {
        return PUBLIC.clone();
    }

    /** Needs the key, unless {@link #publicMatchers()} has already applied. */
    public static RequestMatcher[] keyRequiredMatchers() {
        return KEY_REQUIRED.clone();
    }

    /** Whether the request's path is rejected without a valid key — the same evaluation as the filter chain. */
    public static boolean requiresKey(HttpServletRequest request) {
        return !anyMatches(PUBLIC, request) && anyMatches(KEY_REQUIRED, request);
    }

    private static boolean anyMatches(RequestMatcher[] matchers, HttpServletRequest request) {
        return Arrays.stream(matchers).anyMatch(m -> m.matches(request));
    }

    private static RequestMatcher[] matchers(List<String> patterns) {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        return patterns.stream().map(builder::matcher).toArray(RequestMatcher[]::new);
    }
}
