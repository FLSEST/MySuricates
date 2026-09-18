package com.automation.controller;

import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.service.metrics.RequestMetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Request costs per route: the live window from memory and the stored daily history.
 *
 * <p>Core diagnostics, not a module — this is how a slow platform gets diagnosed, so it must not
 * be switchable off along with a feature.
 */
@RestController
@RequestMapping("/api/v1/system/request-metrics")
@RequiredArgsConstructor
public class SystemRequestMetricsController {

    private final RequestMetricsQueryService queryService;

    /** Routes measured since the last daily snapshot, most expensive (p95) first. */
    @GetMapping
    public List<RouteSnapshot> live() {
        return queryService.liveWindow();
    }

    /**
     * Stored daily rows for one route, oldest first — the baseline a drift check compares against.
     * Without {@code route} the rows of a single day are returned instead.
     */
    @GetMapping("/daily")
    public List<RequestMetricDaily> daily(@RequestParam(required = false) String route,
                                          @RequestParam(defaultValue = "7") int days) {
        if (route == null || route.isBlank()) {
            return queryService.today();
        }
        return queryService.history(route, days);
    }
}
