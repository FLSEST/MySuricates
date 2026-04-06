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
