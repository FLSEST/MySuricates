package com.automation.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCostFilterTest {

    private RequestMetricsConfig config;
    private RequestMetricsCollector collector;
    private RequestCostFilter filter;

    @BeforeEach
    void setUp() {
        config = new RequestMetricsConfig();
        collector = new RequestMetricsCollector();
        filter = new RequestCostFilter(config, collector);
    }

    @AfterEach
    void tearDown() {
        SqlStatementCounter.stop();
        ConnectionWaitCounter.stop();
    }

    /** A handler that issues two statements, waits a little for the connection and writes a body. */
    private static FilterChain handler(String pattern, String body) {
        return (req, res) -> {
            if (pattern != null) {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            }
            SqlStatementCounter.record();
            SqlStatementCounter.record();
            ConnectionWaitCounter.record(3_000_000L);
            res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        };
    }

    private RouteSnapshot only() {
        List<RouteSnapshot> snapshot = collector.snapshot();
        assertThat(snapshot).hasSize(1);
        return snapshot.get(0);
    }

    @Test
    void recordsCostUnderTheRouteTemplateNotTheRawUri() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/todos/42");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, handler("/api/v1/todos/{id}", "0123456789"));

        RouteSnapshot stats = only();
        assertThat(stats.route()).isEqualTo("GET /api/v1/todos/{id}");
        assertThat(stats.calls()).isEqualTo(1);
        assertThat(stats.maxSqlStatements()).isEqualTo(2);
        assertThat(stats.maxResponseBytes()).isEqualTo(10);
        assertThat(response.getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void requestWithoutMatchedPatternIsGroupedAsUnmatched() throws Exception {
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/nowhere/7"),
                new MockHttpServletResponse(), handler(null, "x"));

        assertThat(only().route()).isEqualTo("POST (unmatched)");
    }

    @Test
    void addsServerTimingHeaderBeforeTheBodyIsWritten() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/todos"), response,
                handler("/api/v1/todos", "[]"));

        assertThat(response.getHeader("Server-Timing"))
                .matches("wait;dur=3, work;dur=\\d+, total;dur=\\d+, sql;desc=\"n=2\"");
    }

    /** The header exposes internal timings: switched off, it must not appear at all. */
    @Test
    void noServerTimingHeaderWhenSwitchedOff() throws Exception {
        config.setServerTimingEnabled(false);
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/todos"), response,
                handler("/api/v1/todos", "[]"));

        assertThat(response.getHeader("Server-Timing")).isNull();
        assertThat(collector.snapshot()).hasSize(1);
    }

    @Test
    void emptyResponseIsStillRecordedWithZeroBytes() throws Exception {
        filter.doFilter(new MockHttpServletRequest("DELETE", "/api/v1/todos/1"), new MockHttpServletResponse(),
                (req, res) -> req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/todos/{id}"));

        RouteSnapshot stats = only();
        assertThat(stats.route()).isEqualTo("DELETE /api/v1/todos/{id}");
        assertThat(stats.maxResponseBytes()).isZero();
        assertThat(stats.maxSqlStatements()).isZero();
    }

    @Test
    void nonApiPathsAreNotMeasured() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response,
                handler("/actuator/health", "ok"));

        assertThat(collector.snapshot()).isEmpty();
        assertThat(response.getHeader("Server-Timing")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("ok");
    }

    @Test
    void disabledMeasurementPassesRequestsThroughUntouched() throws Exception {
        config.setEnabled(false);
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/todos"), response,
                handler("/api/v1/todos", "[]"));

        assertThat(collector.snapshot()).isEmpty();
        assertThat(response.getHeader("Server-Timing")).isNull();
    }

    /** A failing handler must still be counted — and must not leak the ThreadLocals into the next request. */
    @Test
    void failingRequestIsRecordedAndCountersAreCleanedUp() {
        FilterChain failing = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/todos");
            SqlStatementCounter.record();
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/todos"),
                new MockHttpServletResponse(), failing)).isInstanceOf(ServletException.class);

        assertThat(only().maxSqlStatements()).isEqualTo(1);
        assertThat(SqlStatementCounter.isActive()).isFalse();
        assertThat(ConnectionWaitCounter.isActive()).isFalse();
    }

    /** An outer measurement (the ratchet test) must survive the filter's own inner one. */
    @Test
    void outerSqlMeasurementKeepsCountingAcrossTheFilter() throws Exception {
        SqlStatementCounter.start();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/todos"), new MockHttpServletResponse(),
                handler("/api/v1/todos", "[]"));

        assertThat(SqlStatementCounter.isActive()).isTrue();
        assertThat(SqlStatementCounter.count()).isEqualTo(2);
    }
}
