package com.automation.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPathsTest {

    private static boolean requiresKey(String path) {
        return PublicPaths.requiresKey(new MockHttpServletRequest("GET", path));
    }

    @Test
    void should_listExactlyTheKeyFreeProbesFromTheProjectRules() {
        // CLAUDE.md: the only key-free probes workflow shell blocks may call
        assertThat(PublicPaths.EXACT).containsExactly(
                "/api/v1/system/health",
                "/api/v1/system/info",
                "/api/v1/system/auth-status",
                "/api/v1/system/jvm-memory",
                "/actuator/health");
    }

    @Test
    void should_listThePublicPatternsAndProtectedAreas() {
        assertThat(PublicPaths.PATTERNS).containsExactly(
                "/ws/**", "/api/v1/webhook/trigger/**", "/api/v1/integrations/*/callback");
        assertThat(PublicPaths.PROTECTED).containsExactly("/actuator/**", "/api/**");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/system/health", "/api/v1/system/info", "/api/v1/system/auth-status",
            "/api/v1/system/jvm-memory", "/actuator/health"})
    void should_openEveryExactProbe(String path) {
        assertThat(requiresKey(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/system/health/",          // trailing slash
            "/api/v1/system/health/details",   // subpath
            "/api/v1/System/health",           // case matters
            "/actuator/metrics",
            "/actuator/metrics/jvm.memory.used",
            "/actuator/health/liveness",
            "/api/v1/system/capabilities",
            "/api/v1/workflows",
            "/api"})
    void should_requireKey_forProtectedPathsThatAreNotExactProbes(String path) {
        assertThat(requiresKey(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/ws", "/ws/raw", "/ws/a/b/c",
            "/api/v1/webhook/trigger/abc", "/api/v1/webhook/trigger/abc/def",
            "/api/v1/integrations/google/callback"})
    void should_openPublicPatterns(String path) {
        assertThat(requiresKey(path)).isFalse();
    }

    @Test
    void should_matchOnlyOneSegment_forTheSingleWildcardInTheCallbackPattern() {
        assertThat(requiresKey("/api/v1/integrations/a/b/callback")).isTrue();
        assertThat(requiresKey("/api/v1/integrations/google/callback/extra")).isTrue();
        assertThat(requiresKey("/api/v1/integrations/google/connect")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/assets/app.js", "/workflows/123", "/apix"})
    void should_leaveTheServedFrontendOpen(String path) {
        assertThat(requiresKey(path)).isFalse();
    }

    @Test
    void should_handOutDefensiveCopiesOfTheMatcherArrays() {
        RequestMatcher[] publicMatchers = PublicPaths.publicMatchers();
        RequestMatcher[] keyRequired = PublicPaths.keyRequiredMatchers();

        assertThat(publicMatchers).hasSize(PublicPaths.EXACT.size() + PublicPaths.PATTERNS.size());
        assertThat(keyRequired).hasSize(PublicPaths.PROTECTED.size());

        Arrays.fill(publicMatchers, null);
        Arrays.fill(keyRequired, null);

        assertThat(PublicPaths.publicMatchers()).doesNotContainNull();
        assertThat(PublicPaths.keyRequiredMatchers()).doesNotContainNull();
        assertThat(requiresKey("/api/v1/workflows")).isTrue();
    }

    @Test
    void should_evaluateTheSameMatchersAsRequiresKey() {
        MockHttpServletRequest probe = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/todos");

        assertThat(Arrays.stream(PublicPaths.publicMatchers()).anyMatch(m -> m.matches(probe))).isTrue();
        assertThat(Arrays.stream(PublicPaths.publicMatchers()).anyMatch(m -> m.matches(api))).isFalse();
        assertThat(Arrays.stream(PublicPaths.keyRequiredMatchers()).anyMatch(m -> m.matches(api))).isTrue();
    }
}
