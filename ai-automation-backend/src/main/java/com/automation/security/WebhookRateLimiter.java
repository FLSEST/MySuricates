package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP rate limiter for the public webhook trigger endpoint.
 * Always active (independent of global rate-limit flag) since webhook
 * triggers are unauthenticated and can start workflow executions.
 */
@Component
public class WebhookRateLimiter {

    private static final int MAX_REQUESTS_PER_MINUTE = 10;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(clientIp, k -> new Bucket());
        return bucket.tryConsume();
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class Bucket {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        private final Object lock = new Object();

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (now - windowStart > 60_000) {
                    count.set(0);
                    windowStart = now;
                }
            }
            return count.incrementAndGet() <= MAX_REQUESTS_PER_MINUTE;
        }
    }
}
