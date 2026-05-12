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

/**
 * Caps the Content-Length of POST /api/v1/activity-log to keep a single plugin
 * log payload from blocking the HikariCP pool (pool-size=1) or starving threads.
 * Activity logs are short text — 64 KiB is generous.
 */
@Component
@Slf4j
public class ActivityLogSizeLimitFilter extends OncePerRequestFilter {

    private static final String TARGET_PATH = "/api/v1/activity-log";

    @Value("${automation.activity-log.max-request-bytes:65536}")
    private long maxBytes;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            log.warn("Activity-log payload rejected: {} bytes exceeds limit {} bytes",
                    contentLength, maxBytes);
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Payload too large\",\"maxBytes\":" + maxBytes + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
