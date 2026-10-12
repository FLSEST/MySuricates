package com.automation.security;

import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private RateLimitConfig rateLimitConfig;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private RunTokenService runTokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimitConfig = new RateLimitConfig();
        filter = new RateLimitFilter(rateLimitConfig, clientIpResolver, apiKeyService, runTokenService);
    }

    @Test
    void rateLimitDisabled_passesThrough() throws Exception {
        rateLimitConfig.setEnabled(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void rateLimitEnabled_underLimit_passesThrough() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(10);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response).setHeader(eq("X-RateLimit-Remaining"), any());
    }

    @Test
    void rateLimitEnabled_exceedsLimit_returns429() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(2);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        // First two requests should pass
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        // Third request should be rate limited
        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
        verify(response).setContentType("application/json");
        writer.flush();
        assertThat(stringWriter.toString()).contains("retryAfterSeconds");
        assertThat(stringWriter.toString()).contains("Rate limit exceeded");
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stringWriter.toString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("params").get("seconds").asText()).isEqualTo(body.get("retryAfterSeconds").asText());
    }

    @Test
    void rateLimitEnabled_exceedsLimit_sendsRetryAfterHeaderMatchingBody() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        // The client bases its lockout on Retry-After; without the header it guesses 10 s. A
        // fresh window means: just under 60 s (the exact calculation is checked by secondsUntilReset_*).
        ArgumentCaptor<String> retryAfter = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq("Retry-After"), retryAfter.capture());
        int seconds = Integer.parseInt(retryAfter.getValue());
        assertThat(seconds).isEqualTo(60);

        writer.flush();
        // The same value in the body — header and JSON must not diverge.
        assertThat(stringWriter.toString()).contains("\"retryAfterSeconds\": " + seconds);
    }

    @Test
    void runToken_hasItsOwnBucket_notTheSharedLocalhostOne() throws Exception {
        // A run token comes from 127.0.0.1 like the UI and local agents; a busy run must not use up their bucket
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(request.getHeader("X-Run-Token")).thenReturn("tok-a");
        when(runTokenService.resolve("tok-a")).thenReturn(java.util.Optional.of("run-1"));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        HttpServletRequest local = mock(HttpServletRequest.class);
        HttpServletResponse localResponse = mock(HttpServletResponse.class);
        when(clientIpResolver.resolve(local)).thenReturn("127.0.0.1");
        filter.doFilterInternal(local, localResponse, filterChain);

        verify(response).setStatus(429);                 // the run's second request
        verify(localResponse, never()).setStatus(429);   // the UI's first one still passes
        verify(clientIpResolver, never()).resolve(request);
        assertThat(RateLimitFilter.clientKey("tok-a")).isNotEqualTo(RateLimitFilter.clientKey("tok-b"))
                .doesNotContain("tok-a");
    }

    @Test
    void invalidRunTokens_doNotEscapeThePerIpLimit() throws Exception {
        // A fresh random X-Run-Token per request must not buy a fresh bucket each time (review of cfb5d56b):
        // otherwise API-key guessing with an invalid token alongside would never hit the limit
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(2);
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.7");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        AtomicInteger n = new AtomicInteger();
        when(request.getHeader("X-Run-Token")).thenAnswer(inv -> "random-" + n.incrementAndGet());
        when(runTokenService.resolve(org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.Optional.empty());

        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
    }

    @Test
    void secondsUntilReset_countsDownWithinTheWindow() {
        long start = 1_700_000_000_000L;
        RateLimitFilter.ClientBucket bucket = new RateLimitFilter.ClientBucket(start);

        // Fixed point in time instead of a span: a hard-wired 60 would otherwise slip through here.
        assertThat(bucket.secondsUntilReset(start)).isEqualTo(60);
        assertThat(bucket.secondsUntilReset(start + 30_000)).isEqualTo(30);
        assertThat(bucket.secondsUntilReset(start + 59_001)).isEqualTo(1);
        assertThat(bucket.secondsUntilReset(start + 59_999)).isEqualTo(1);
    }

    @Test
    void secondsUntilReset_neverReturnsZero() {
        long start = 1_700_000_000_000L;
        RateLimitFilter.ClientBucket bucket = new RateLimitFilter.ClientBucket(start);

        // 0 would mean "no lockout" to the client — it would immediately request again.
        assertThat(bucket.secondsUntilReset(start + 60_000)).isEqualTo(1);
        assertThat(bucket.secondsUntilReset(start + 120_000)).isEqualTo(1);
    }

    @Test
    void rateLimitEnabled_underLimit_sendsNoRetryAfterHeader() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(10);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");

        filter.doFilterInternal(request, response, filterChain);

        verify(response, never()).setHeader(eq("Retry-After"), any());
    }

    @Test
    void rateLimitEnabled_usesResolvedClientIp() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");

        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);

        // First passes
        filter.doFilterInternal(request, response, filterChain);
        // Second blocked (for same forwarded IP)
        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
    }

    @Test
    void rateLimitEnabled_authenticatedInternalSync_bypassesLimit() {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn("true");
        when(request.getHeader("X-API-Key")).thenReturn("sync-key");
        when(apiKeyService.matchesEffectiveApiKey("sync-key")).thenReturn(true);

        boolean shouldSkip = filter.shouldNotFilter(request);

        assertThat(shouldSkip).isTrue();
    }

    @Test
    void rateLimitEnabled_spoofedInternalSyncWithInvalidKey_notBypassed() {
        rateLimitConfig.setEnabled(true);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn("true");
        when(request.getHeader("X-API-Key")).thenReturn("attacker-key");

        boolean shouldSkip = filter.shouldNotFilter(request);

        assertThat(shouldSkip).isFalse();
        verify(apiKeyService).matchesEffectiveApiKey("attacker-key");
    }

    @Test
    void rateLimitEnabled_internalSyncWithoutKey_notBypassed() {
        rateLimitConfig.setEnabled(true);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn("true");

        boolean shouldSkip = filter.shouldNotFilter(request);

        assertThat(shouldSkip).isFalse();
        verify(apiKeyService).matchesEffectiveApiKey(null);
    }

    @Test
    void rateLimitEnabled_noInternalSyncHeader_notBypassed() {
        rateLimitConfig.setEnabled(true);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn(null);

        boolean shouldSkip = filter.shouldNotFilter(request);

        assertThat(shouldSkip).isFalse();
        verifyNoInteractions(apiKeyService);
    }

    @Test
    void staticAssets_bypassRateLimit() {
        rateLimitConfig.setEnabled(true);
        // Static frontend bundle chunks must never count against the API bucket —
        // a single workflow-detail page mount lazy-loads dozens of chunks (Monaco,
        // Workbox, route chunks) and would otherwise exhaust the limit and 429
        // legitimate <script> loads, breaking Monaco initialisation.
        for (String path : List.of("/", "/index.html", "/assets/index-abc.js",
                "/assets/index-abc.css", "/sw.js", "/workbox-1234.js",
                "/manifest.webmanifest", "/ws/info", "/actuator/health")) {
            when(request.getRequestURI()).thenReturn(path);
            assertThat(filter.shouldNotFilter(request))
                    .as("path %s should bypass rate limit", path)
                    .isTrue();
        }
    }

    @Test
    void rateLimitEnabled_concurrentRequests_exactlyLimitPass() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(10);
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.99");

        int totalThreads = 20;
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger blocked = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    HttpServletRequest req = mock(HttpServletRequest.class);
                    HttpServletResponse resp = mock(HttpServletResponse.class);
                    FilterChain chain = mock(FilterChain.class);
                    StringWriter sw = new StringWriter();
                    when(resp.getWriter()).thenReturn(new PrintWriter(sw));

                    ready.countDown();
                    go.await();

                    filter.doFilterInternal(req, resp, chain);

                    try {
                        verify(chain).doFilter(req, resp);
                        passed.incrementAndGet();
                    } catch (AssertionError e) {
                        blocked.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads.add(t);
            t.start();
        }

        ready.await();
        go.countDown();

        for (Thread t : threads) {
            t.join(5000);
        }

        assertThat(passed.get()).isEqualTo(10);
        assertThat(blocked.get()).isEqualTo(10);
    }

    @Test
    void rateLimitEnabled_differentClients_trackedSeparately() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);

        HttpServletRequest request2 = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");
        when(clientIpResolver.resolve(request2)).thenReturn("10.0.0.2");

        // Both first requests should pass
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request2, response, filterChain);

        verify(filterChain, times(2)).doFilter(any(), eq(response));
        verify(response, never()).setStatus(429);
    }
}
