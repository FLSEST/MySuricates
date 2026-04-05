package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookRateLimiterTest {

    private WebhookRateLimiter rateLimiter;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        rateLimiter = new WebhookRateLimiter();
    }

    @Test
    void should_allowRequest_when_underLimit() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        boolean result = rateLimiter.tryAcquire(request);

        assertThat(result).isTrue();
    }

    @Test
    void should_allowFirst10Requests_when_sameIp() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.tryAcquire(request))
                    .as("Request %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void should_blockRequest_when_limitExceeded() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        // Exhaust 10-request limit
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(request);
        }

        // 11th request should be blocked
        assertThat(rateLimiter.tryAcquire(request)).isFalse();
    }

    @Test
    void should_blockAllSubsequentRequests_when_limitExceeded() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        // Exhaust limit
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(request);
        }

        // Requests 11, 12, 13 should all be blocked
        assertThat(rateLimiter.tryAcquire(request)).isFalse();
        assertThat(rateLimiter.tryAcquire(request)).isFalse();
        assertThat(rateLimiter.tryAcquire(request)).isFalse();
    }

    @Test
    void should_useForwardedForIp_when_headerPresent() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");
        // remoteAddr should not be used when X-Forwarded-For is present

        boolean result = rateLimiter.tryAcquire(request);

        assertThat(result).isTrue();
        // Verify remoteAddr was never called (X-Forwarded-For took precedence)
        verify(request, never()).getRemoteAddr();
    }

    @Test
    void should_useFirstIpFromForwardedFor_when_multipleIps() {
        // First request with forwarded IP "10.0.0.1, 10.0.0.2"
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");

        // Exhaust limit for the first IP (10.0.0.1)
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(request);
        }

        // 11th request should be blocked (counted under 10.0.0.1)
        assertThat(rateLimiter.tryAcquire(request)).isFalse();

        // A request from the second IP (10.0.0.2) should still be allowed
        HttpServletRequest request2 = mock(HttpServletRequest.class);
        when(request2.getHeader("X-Forwarded-For")).thenReturn("10.0.0.2");
        assertThat(rateLimiter.tryAcquire(request2)).isTrue();
    }

    @Test
    void should_useRemoteAddr_when_forwardedForIsNull() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        assertThat(rateLimiter.tryAcquire(request)).isTrue();
        verify(request).getRemoteAddr();
    }

    @Test
    void should_useRemoteAddr_when_forwardedForIsBlank() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        assertThat(rateLimiter.tryAcquire(request)).isTrue();
        verify(request).getRemoteAddr();
    }

    @Test
    void should_trackDifferentIpsSeparately() {
        HttpServletRequest request2 = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request2.getRemoteAddr()).thenReturn("192.168.1.2");

        // Exhaust limit for first IP
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(request);
        }

        // First IP should be blocked
        assertThat(rateLimiter.tryAcquire(request)).isFalse();

        // Second IP should still be allowed
        assertThat(rateLimiter.tryAcquire(request2)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_resetWindow_when_60secondsElapsed() throws Exception {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");

        // Exhaust limit
        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(request);
        }
        assertThat(rateLimiter.tryAcquire(request)).isFalse();

        // Simulate time passing by manipulating the bucket's windowStart
        Map<String, Object> buckets = (Map<String, Object>) ReflectionTestUtils.getField(rateLimiter, "buckets");
        assertThat(buckets).isNotNull();
        Object bucket = buckets.get("192.168.1.1");
        assertThat(bucket).isNotNull();

        // Set windowStart to 61 seconds ago
        long pastTime = System.currentTimeMillis() - 61_000;
        ReflectionTestUtils.setField(bucket, "windowStart", pastTime);

        // Should be allowed again after window reset
        assertThat(rateLimiter.tryAcquire(request)).isTrue();
    }

    @Test
    void should_trimWhitespace_when_forwardedForHasSpaces() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("  10.0.0.1  , 10.0.0.2");

        // This should work and use trimmed "10.0.0.1"
        assertThat(rateLimiter.tryAcquire(request)).isTrue();

        // Verify it's tracked under the same IP as a clean request
        HttpServletRequest request2 = mock(HttpServletRequest.class);
        when(request2.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");

        // Both should share the same bucket - exhaust remaining 9
        for (int i = 0; i < 9; i++) {
            rateLimiter.tryAcquire(request2);
        }
        // 11th request (across both request objects) should be blocked
        assertThat(rateLimiter.tryAcquire(request)).isFalse();
    }
}
