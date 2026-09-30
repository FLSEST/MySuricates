package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Die eine Liste, welche Pfade ohne API-Key erreichbar sind. {@code SecurityConfig} baut daraus die
 * Autorisierungsregeln, {@link ApiKeyAuthFilter} entscheidet damit, ob ein fehlender Key eine
 * SECURITY-Warnung wert ist — beide werten dieselben {@link RequestMatcher}-Objekte aus, damit Regel
 * und Protokoll nicht wieder auseinanderlaufen (früher galt der OAuth-Callback der Config als
 * öffentlich, dem Filter als geschützt).
 *
 * <p>Reihenfolge der Auswertung, wie in der Filterkette: zuerst {@link #EXACT} und {@link #PATTERNS}
 * (frei), dann {@link #PROTECTED} (Key nötig), alles andere ist frei (das ausgelieferte Frontend).
 *
 * <p>Gematcht wird mit {@link PathPatternRequestMatcher} (Spring-{@code PathPattern} auf dem Pfad ohne
 * Context-Path): exakte Pfade treffen nur sich selbst — kein angehängter Schrägstrich, kein
 * Unterpfad, Groß-/Kleinschreibung zählt —, {@code *} steht für genau ein Segment, {@code /**} für
 * null oder mehr Segmente.
 */
public final class PublicPaths {

    private PublicPaths() {}

    /**
     * Schlüsselfreie Sonden: exakte Pfade ohne Platzhalter. Die Lebenszeichen- und Heap-Sonden, die
     * Workflows ohne Key rufen dürfen ({@code SelfAccessRules.KEY_FREE_PROBES},
     * {@code HYGIENE_POOL_API_ALLOWED}), müssen hier stehen.
     */
    public static final List<String> EXACT = List.of(
            "/api/v1/system/health",
            "/api/v1/system/info",
            "/api/v1/system/auth-status",
            // Genau drei Heap-Zahlen für den Performance-Audit; die Metrik-Oberfläche unter
            // /actuator/metrics bleibt hinter dem Key.
            "/api/v1/system/jvm-memory",
            "/actuator/health");

    /**
     * Öffentliche Muster: der WebSocket-Handshake (prüft sein Ticket selbst), der Webhook-Eingang
     * (prüft Token und Signatur selbst) und die OAuth-Rückrufe der Integrationen (der Anbieter ruft
     * ohne Key zurück, geprüft wird der {@code state}).
     */
    public static final List<String> PATTERNS = List.of(
            "/ws/**",
            "/api/v1/webhook/trigger/**",
            "/api/v1/integrations/*/callback");

    /** Bereiche, in denen alles außerhalb von {@link #EXACT} und {@link #PATTERNS} den Key braucht. */
    public static final List<String> PROTECTED = List.of(
            "/actuator/**",
            "/api/**");

    private static final RequestMatcher[] PUBLIC =
            matchers(Stream.concat(EXACT.stream(), PATTERNS.stream()).toList());
    private static final RequestMatcher[] KEY_REQUIRED = matchers(PROTECTED);

    /** Ohne Key erreichbar — in der Filterkette vor {@link #keyRequiredMatchers()} ausgewertet. */
    public static RequestMatcher[] publicMatchers() {
        return PUBLIC.clone();
    }

    /** Braucht den Key, sofern nicht schon {@link #publicMatchers()} gegriffen hat. */
    public static RequestMatcher[] keyRequiredMatchers() {
        return KEY_REQUIRED.clone();
    }

    /** Ob der Pfad des Requests ohne gültigen Key abgewiesen wird — dieselbe Auswertung wie die Filterkette. */
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
