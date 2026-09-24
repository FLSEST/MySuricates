package com.automation.controller;

import com.automation.entity.RequestMetricDaily;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.metrics.RequestMetricsQueryService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemRequestMetricsController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemRequestMetricsControllerTest {

    private static final String ROUTE = "GET /api/v1/todos";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RequestMetricsQueryService queryService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private static RequestMetricDaily row(LocalDate day, long p95) {
        return RequestMetricDaily.builder().id("r-" + p95).day(day).route(ROUTE).calls(40).p95Ms(p95).build();
    }

    @Test
    void liveWindowIsReturnedAsIs() throws Exception {
        when(queryService.liveWindow()).thenReturn(List.of(
                new RouteSnapshot(ROUTE, 42, 10, 80, 900, 20, 0.25, 7, 3.5, 40_000, 8_000)));

        mockMvc.perform(get("/api/v1/system/request-metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].route").value(ROUTE))
                .andExpect(jsonPath("$[0].calls").value(42))
                .andExpect(jsonPath("$[0].p95Ms").value(80))
                .andExpect(jsonPath("$[0].waitShare").value(0.25))
                .andExpect(jsonPath("$[0].maxSqlStatements").value(7));
    }

    @Test
    void dailyWithoutRouteReturnsTodaysRows() throws Exception {
        when(queryService.today()).thenReturn(List.of(row(LocalDate.of(2026, 9, 21), 80)));

        mockMvc.perform(get("/api/v1/system/request-metrics/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].route").value(ROUTE))
                .andExpect(jsonPath("$[0].p95Ms").value(80))
                .andExpect(jsonPath("$[0].day").value("2026-09-21"));

        verify(queryService, never()).history(anyString(), anyInt());
    }

    @Test
    void blankRouteCountsAsNoRoute() throws Exception {
        when(queryService.today()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/system/request-metrics/daily").param("route", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(queryService).today();
        verify(queryService, never()).history(anyString(), anyInt());
    }

    @Test
    void dailyWithRouteReturnsItsHistoryForSevenDaysByDefault() throws Exception {
        when(queryService.history(ROUTE, 7)).thenReturn(List.of(
                row(LocalDate.of(2026, 9, 19), 60), row(LocalDate.of(2026, 9, 20), 70)));

        mockMvc.perform(get("/api/v1/system/request-metrics/daily").param("route", ROUTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].p95Ms").value(70));
    }

    @Test
    void daysArePassedThrough() throws Exception {
        when(queryService.history(ROUTE, 30)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/system/request-metrics/daily").param("route", ROUTE).param("days", "30"))
                .andExpect(status().isOk());

        verify(queryService).history(ROUTE, 30);
    }

    @Test
    void nonNumericDaysIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/system/request-metrics/daily").param("route", ROUTE).param("days", "week"))
                .andExpect(status().isBadRequest());

        verify(queryService, never()).history(anyString(), anyInt());
    }
}
