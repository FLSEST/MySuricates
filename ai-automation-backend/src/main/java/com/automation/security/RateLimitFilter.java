package com.automation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${automation.security.rate-limit.requests-per-minute:120}")
    private int requestsPerMinute;

    @Value("${automation.security.rate-limit.enabled:false}")
    private boolean rateLimitEnabled;

    @Value("${automation.security.trusted-proxies:127.0.0.1,::1}")
    private String trustedProxies;

    private final Map<String, ClientBucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/ws") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientId = getClientId(request);
        ClientBucket bucket = buckets.computeIfAbsent(clientId, k -> new ClientBucket());

        if (bucket.tryConsume(requestsPerMinute)) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getRemaining(requestsPerMinute)));
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

    private String getClientId(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (Set.of(trustedProxies.split(",")).contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
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
