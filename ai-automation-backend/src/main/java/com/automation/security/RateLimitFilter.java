package com.automation.security;

import com.automation.config.RateLimitConfig;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final ClientIpResolver clientIpResolver;
    private final ApiKeyService apiKeyService;

    private final Map<String, ClientBucket> buckets = new ConcurrentHashMap<>();

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
        ClientBucket bucket = buckets.computeIfAbsent(clientId, k -> new ClientBucket());

        if (bucket.tryConsume(limit)) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getRemaining(limit)));
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for client: {}", clientId);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error": "Rate limit exceeded", "retryAfterSeconds": 60}
                    """);
        }
    }

    private static class ClientBucket {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        private final Object windowLock = new Object();

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
