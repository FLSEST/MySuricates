package com.automation.metrics;

import com.automation.config.RequestMetricsConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Measures what one API request costs: total time, time waited for the database connection,
 * SQL statements and response bytes (TODO 5d1c3b2a).
 *
 * <p>Registered through a {@code FilterRegistrationBean} in {@code RequestMetricsFilterConfig},
 * deliberately not as a {@code @Component}: component-scanned {@code Filter} beans are
 * instantiated by every {@code @WebMvcTest} slice, which would force roughly four hundred
 * controller tests to mock this filter's dependencies. Ordered outermost there, so the
 * measurement includes authentication and rate limiting — a request held up in those is slow
 * for the user just the same.
 *
 * <p>Routes are keyed by their <em>template</em> ({@code GET /api/v1/todos/{id}}), taken from
 * Spring's best-matching-pattern attribute after the handler ran. Keying by the raw URI would
 * make every id its own metric.
 */
@RequiredArgsConstructor
@Slf4j
public class RequestCostFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1";

    private final RequestMetricsConfig config;
    private final RequestMetricsCollector collector;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.isEnabled() || !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        SqlStatementCounter.start();
        ConnectionWaitCounter.start();

        MeasuredResponse measured = new MeasuredResponse(response,
                () -> addServerTiming(response, startNanos));
        try {
            chain.doFilter(request, measured);
        } finally {
            measured.finish();
            long totalMs = elapsedMs(startNanos);
            long waitMs = TimeUnit.NANOSECONDS.toMillis(ConnectionWaitCounter.waitedNanos());
            int statements = SqlStatementCounter.count();
            SqlStatementCounter.stop();
            ConnectionWaitCounter.stop();

            String route = routeOf(request);
            collector.record(route, totalMs, waitMs, statements, measured.bytesWritten());
            warnIfSlow(route, totalMs, waitMs, statements, measured.bytesWritten());
        }
    }

    /**
     * A single slow request is logged, not written to the ActivityLog: with
     * {@code maximum-pool-size: 1} an extra database write on the thread of an already-slow
     * request would compete for the very connection it is waiting for. Persistent drift is what
     * reaches the ActivityLog, and that runs on the watchdog's own thread (TODO d86bed62).
     */
    private void warnIfSlow(String route, long totalMs, long waitMs, int statements, long bytes) {
        long threshold = config.getSlowRequestMs();
        if (threshold <= 0 || totalMs < threshold) return;
        log.warn("Slow request {} — {} ms total, {} ms waiting for the DB connection, {} SQL statements, {} bytes",
                route, totalMs, waitMs, statements, bytes);
    }

    private void addServerTiming(HttpServletResponse response, long startNanos) {
        if (!config.isServerTimingEnabled() || response.isCommitted()) return;
        long waitMs = TimeUnit.NANOSECONDS.toMillis(ConnectionWaitCounter.waitedNanos());
        long soFarMs = elapsedMs(startNanos);
        response.addHeader("Server-Timing", String.format(
                "wait;dur=%d, work;dur=%d, total;dur=%d, sql;desc=\"n=%d\"",
                waitMs, Math.max(soFarMs - waitMs, 0), soFarMs, SqlStatementCounter.count()));
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String template = pattern instanceof String s && !s.isBlank() ? s : "(unmatched)";
        return request.getMethod() + " " + template;
    }
}
