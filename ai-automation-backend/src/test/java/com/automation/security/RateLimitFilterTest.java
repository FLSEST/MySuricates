package com.automation.security;

import com.automation.config.RateLimitConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimitConfig = new RateLimitConfig();
        filter = new RateLimitFilter(rateLimitConfig);
        ReflectionTestUtils.setField(filter, "trustedProxies", "127.0.0.1,::1");
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
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response).setHeader(eq("X-RateLimit-Remaining"), any());
    }

    @Test
    void rateLimitEnabled_exceedsLimit_returns429() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(2);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

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
    }

    @Test
    void rateLimitEnabled_usesForwardedFor() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");

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
    void rateLimitEnabled_internalSync_bypassesLimit() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(1);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn("true");

        // Should bypass rate limit entirely (shouldNotFilter returns true)
        boolean shouldSkip = filter.shouldNotFilter(request);
        assertThat(shouldSkip).isTrue();
    }

    @Test
    void rateLimitEnabled_noInternalSyncHeader_notBypassed() throws Exception {
        rateLimitConfig.setEnabled(true);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/import");
        when(request.getHeader("X-Internal-Sync")).thenReturn(null);

        boolean shouldSkip = filter.shouldNotFilter(request);
        assertThat(shouldSkip).isFalse();
    }

    @Test
    void rateLimitEnabled_concurrentRequests_exactlyLimitPass() throws Exception {
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setRequestsPerMinute(10);

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
                    when(req.getRemoteAddr()).thenReturn("10.0.0.99");
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
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request2.getRemoteAddr()).thenReturn("10.0.0.2");

        // Both first requests should pass
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request2, response, filterChain);

        verify(filterChain, times(2)).doFilter(any(), eq(response));
        verify(response, never()).setStatus(429);
    }
}
