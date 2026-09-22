package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP rate limiter for the public webhook trigger endpoint.
 * Always active (independent of global rate-limit flag) since webhook
 * triggers are unauthenticated and can start workflow executions.
 */
@Component
public class WebhookRateLimiter {

    private static final int MAX_REQUESTS_PER_MINUTE = 10;

    private final ClientIpResolver clientIpResolver;
    /** Bounded and swept on access (ba6a91a6). */
    private final ClientBucketStore<Bucket> buckets = new ClientBucketStore<>();

    @Autowired
    public WebhookRateLimiter(
            @Value("${automation.security.trusted-proxies:127.0.0.1,::1}") String trustedProxies) {
        this(new ClientIpResolver(trustedProxies));
    }

    WebhookRateLimiter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    public boolean tryAcquire(HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        Bucket bucket = buckets.get(clientIp, Bucket::new, System.currentTimeMillis());
        return bucket.tryConsume();
    }

    private static class Bucket implements ClientBucketStore.Windowed {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        private final Object lock = new Object();

        @Override
        public long windowStart() {
            return windowStart;
        }

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
