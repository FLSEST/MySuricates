package com.automation.integration;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import com.automation.service.metrics.RequestMetricsSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the measurement works in the real request path, not just in a unit test: the filter sees
 * the route, the SQL counter is fed by Hibernate, the connection wait is timed through the wrapped
 * DataSource, and the daily snapshot survives in the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class RequestMetricsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestMetricsCollector collector;
    @Autowired private RequestMetricsConfig config;
    @Autowired private RequestMetricsSnapshotService snapshotService;
    @Autowired private RequestMetricDailyRepository repository;

    @BeforeEach
    void resetWindow() {
        collector.drainWindows();
        config.setEnabled(true);
        config.setServerTimingEnabled(false);
    }

    private Optional<RouteSnapshot> routeStats(String route) {
        return collector.snapshot().stream().filter(s -> s.route().equals(route)).findFirst();
    }

    @Test
    void measuresARequestUnderItsRouteTemplate() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());

        Optional<RouteSnapshot> stats = routeStats("GET /api/v1/todos");
        assertThat(stats).isPresent();
        assertThat(stats.get().calls()).isEqualTo(1);
    }

    /** The SQL counter has to be wired into Hibernate, or every cost reads as zero statements. */
    @Test
    void countsTheSqlStatementsOfTheRequest() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());

        assertThat(routeStats("GET /api/v1/todos").orElseThrow().maxSqlStatements())
                .as("a list request must issue at least one statement")
                .isPositive();
    }

    @Test
    void countsTheResponseBytes() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk()).andReturn();

        long measured = routeStats("GET /api/v1/todos").orElseThrow().maxResponseBytes();
        assertThat(measured).isEqualTo(result.getResponse().getContentAsByteArray().length);
    }

    /**
     * Ids must not become metrics of their own — otherwise the map grows with the data and the
     * numbers never aggregate.
     */
    @Test
    void parameterisedPathsCollapseIntoOneRoute() throws Exception {
        mockMvc.perform(get("/api/v1/todos/does-not-exist-1"));
        mockMvc.perform(get("/api/v1/todos/does-not-exist-2"));

        assertThat(collector.snapshot()).extracting(RouteSnapshot::route)
                .doesNotContain("GET /api/v1/todos/does-not-exist-1")
                .contains("GET /api/v1/todos/{id}");
        assertThat(routeStats("GET /api/v1/todos/{id}").orElseThrow().calls()).isEqualTo(2);
    }

    @Test
    void nonApiPathsAreNotMeasured() throws Exception {
        mockMvc.perform(get("/actuator/health"));

        assertThat(collector.snapshot()).extracting(RouteSnapshot::route)
                .noneMatch(route -> route.contains("actuator"));
    }

    @Test
    void disabledMeansNoMeasurement() throws Exception {
        config.setEnabled(false);
        try {
            mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());
        } finally {
            config.setEnabled(true);
        }

        assertThat(routeStats("GET /api/v1/todos")).isEmpty();
    }

    /** The connection wait is what tells a slow view apart from a blocked pool. */
    @Test
    void connectionWaitIsMeasuredSeparately() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());

        RouteSnapshot stats = routeStats("GET /api/v1/todos").orElseThrow();
        assertThat(stats.waitShare())
                .as("a request that touched the database has a wait share between 0 and 1")
                .isBetween(0.0d, 1.0d);
    }

    /**
     * On by default, because the header is the fastest diagnosis path while developing and the dev
     * instance runs with no profile set. The profiles that face outwards switch it off explicitly
     * (application-stable/prod/test.yml).
     */
    @Test
    void serverTimingHeaderIsOnByDefault() throws Exception {
        config.setServerTimingEnabled(new RequestMetricsConfig().isServerTimingEnabled());

        MvcResult result = mockMvc.perform(get("/api/v1/todos")).andReturn();

        assertThat(result.getResponse().getHeader("Server-Timing")).isNotNull();
    }

    @Test
    void serverTimingHeaderCanBeSwitchedOff() throws Exception {
        config.setServerTimingEnabled(false);

        MvcResult result = mockMvc.perform(get("/api/v1/todos")).andReturn();

        assertThat(result.getResponse().getHeader("Server-Timing")).isNull();
    }

    @Test
    void serverTimingHeaderCarriesWaitWorkAndSqlWhenEnabled() throws Exception {
        config.setServerTimingEnabled(true);
        MvcResult result;
        try {
            result = mockMvc.perform(get("/api/v1/todos")).andReturn();
        } finally {
            config.setServerTimingEnabled(false);
        }

        assertThat(result.getResponse().getHeader("Server-Timing"))
                .isNotNull()
                .contains("wait;dur=")
                .contains("work;dur=")
                .contains("total;dur=")
                .contains("sql;desc=");
    }

    @Test
    void snapshotWritesOneRowPerRouteAndClearsTheWindow() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());

        int written = snapshotService.persistWindows();

        assertThat(written).isPositive();
        assertThat(collector.snapshot()).isEmpty();
        List<RequestMetricDaily> rows = repository.findByDayOrderByP95MsDesc(LocalDate.now());
        assertThat(rows).extracting(RequestMetricDaily::getRoute).contains("GET /api/v1/todos");
        repository.deleteAll(rows);
    }

    /**
     * Running the job twice in a day must fold the second window into the row, not add a second
     * row and not overwrite the first window's calls (a restart persists mid-day, TODO cab7d1d9).
     */
    @Test
    void snapshotIsRepeatableWithinADay() throws Exception {
        // A context closed earlier in this JVM persists its window on shutdown into the same file.
        repository.deleteAll(repository.findByDayOrderByP95MsDesc(LocalDate.now()));
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());
        snapshotService.persistWindows();
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());
        snapshotService.persistWindows();

        List<RequestMetricDaily> rows = repository.findByDayOrderByP95MsDesc(LocalDate.now()).stream()
                .filter(row -> row.getRoute().equals("GET /api/v1/todos"))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCalls()).isEqualTo(2);
        repository.deleteAll(repository.findByDayOrderByP95MsDesc(LocalDate.now()));
    }

    @Test
    void endpointServesTheLiveWindow() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/system/request-metrics"))
                .andExpect(status().isOk());
        assertThat(routeStats("GET /api/v1/system/request-metrics")).isPresent();
    }
}
