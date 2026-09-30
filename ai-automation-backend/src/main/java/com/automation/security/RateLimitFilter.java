package com.automation.security;

import com.automation.exception.ErrorCode;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final ClientIpResolver clientIpResolver;
    private final ApiKeyService apiKeyService;

    /** Bounded and swept on access — one entry per client, not per request (ba6a91a6). */
    private final ClientBucketStore<ClientBucket> buckets = new ClientBucketStore<>();

    @Autowired
    public RateLimitFilter(
            RateLimitConfig rateLimitConfig,
            ApiKeyService apiKeyService,
            @Value("${automation.security.trusted-proxies:127.0.0.1,::1}") String trustedProxies) {
        this(rateLimitConfig, new ClientIpResolver(trustedProxies), apiKeyService);
    }

    RateLimitFilter(RateLimitConfig rateLimitConfig,
                    ClientIpResolver clientIpResolver,
                    ApiKeyService apiKeyService) {
        this.rateLimitConfig = rateLimitConfig;
        this.clientIpResolver = clientIpResolver;
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only API endpoints are rate-limited. Static frontend assets (/assets/*,
        // /sw.js, Monaco/Workbox chunks, index.html), WebSocket (/ws) and actuator
        // are exempt — they were previously counted against the bucket and a single
        // workflow-detail mount could exhaust 120 RPM, 429'ing lazy script chunks
        // and breaking Monaco initialisation.
        if (!path.startsWith("/api/")) {
            return true;
        }
        // Internal sync traffic bypasses rate limiting only when it proves that it
        // is an authenticated instance-to-instance request. The marker alone is
        // client-controlled and must never be a trust boundary.
        return "true".equals(request.getHeader("X-Internal-Sync"))
                && apiKeyService.matchesEffectiveApiKey(request.getHeader("X-API-Key"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!rateLimitConfig.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit = rateLimitConfig.getRequestsPerMinute();
        String clientId = clientIpResolver.resolve(request);
        ClientBucket bucket = buckets.get(clientId, ClientBucket::new, System.currentTimeMillis());

        if (bucket.tryConsume(limit)) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getRemaining(limit)));
            filterChain.doFilter(request, response);
        } else {
            // Wartezeit bis zum nächsten freien Slot, nicht pauschal 60 s: der Client richtet
            // seine Sperre nach Retry-After und würde ohne den Header 10 s raten (b6feccbc).
            int retryAfterSeconds = bucket.secondsUntilReset(System.currentTimeMillis());
            log.warn("Rate limit exceeded for client: {} (retry after {}s)", clientId, retryAfterSeconds);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            // Written by hand like the size-limit filter: same fields as ErrorResponse plus retryAfterSeconds
            response.getWriter().write("{\"code\":\"" + ErrorCode.RATE_LIMITED.name()
                    + "\",\"error\":\"" + ErrorCode.RATE_LIMITED.getDefaultMessage()
                    + "\",\"timestamp\":\"" + Instant.now()
                    + "\",\"params\":{\"seconds\":\"" + retryAfterSeconds + "\"}"
                    + ",\"retryAfterSeconds\": " + retryAfterSeconds + "}\n");
        }
    }

    /** Package-private, damit {@code RateLimitFilterTest} das Fenster mit festen Zeitpunkten prüfen kann. */
    static class ClientBucket implements ClientBucketStore.Windowed {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart;
        private final Object windowLock = new Object();

        ClientBucket() {
            this(System.currentTimeMillis());
        }

        ClientBucket(long windowStart) {
            this.windowStart = windowStart;
        }

        @Override
        public long windowStart() {
            return windowStart;
        }

        boolean tryConsume(int limit) {
            long now = System.currentTimeMillis();
            synchronized (windowLock) {
                if (now - windowStart > 60_000) {
                    count.set(0);
                    windowStart = now;
                }
            }
            return count.incrementAndGet() <= limit;
        }

        /**
         * Sekunden bis das Minutenfenster umschlägt — mindestens 1, damit ein Client, der in der
         * letzten Millisekunde des Fensters abgewiesen wird, nicht "0" (= keine Sperre) liest.
         */
        int secondsUntilReset(long now) {
            long start;
            synchronized (windowLock) {
                start = windowStart;
            }
            long remainingMs = start + 60_000 - now;
            if (remainingMs <= 0) {
                return 1;
            }
            return (int) Math.min(60, Math.max(1, (remainingMs + 999) / 1000));
        }

        int getRemaining(int limit) {
            long now = System.currentTimeMillis();
            synchronized (windowLock) {
                if (now - windowStart > 60_000) {
                    return limit;
                }
                return Math.max(0, limit - count.get());
            }
        }
    }
}
