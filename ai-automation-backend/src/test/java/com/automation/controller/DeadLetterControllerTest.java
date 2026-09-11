package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.DeadLetterEntry;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dlq.DeadLetterRetryService;
import com.automation.service.dlq.DeadLetterService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeadLetterController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DeadLetterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeadLetterService dlqService;

    @MockitoBean
    private DeadLetterRetryService dlqRetryService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private DeadLetterEntry sampleEntry;

    @BeforeEach
    void setUp() {
        reset(dlqRetryService, dlqService);
        sampleEntry = DeadLetterEntry.builder()
                .id("dlq-1")
                .workflowId("wf-1")
                .workflowRunId("run-1")
                .workflowName("Test Workflow")
                .errorMessage("boom")
                .failedStepIndex(2)
                .retryCount(0)
                .lastFailureAt(Instant.now())
                .status(DeadLetterEntry.Status.OPEN)
                .build();
    }

    @Test
    void should_return_paged_entries_when_listing_without_status() throws Exception {
        Page<DeadLetterEntry> page = new PageImpl<>(List.of(sampleEntry));
        when(dlqService.list(eq(null), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("dlq-1"))
                .andExpect(jsonPath("$.content[0].workflowName").value("Test Workflow"));

        verify(dlqService).list(eq(null), any(Pageable.class));
    }

    @Test
    void should_filter_by_status_when_listing_with_status_param() throws Exception {
        Page<DeadLetterEntry> page = new PageImpl<>(List.of(sampleEntry));
        when(dlqService.list(eq(DeadLetterEntry.Status.OPEN), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/dlq").param("status", "OPEN").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("dlq-1"));

        verify(dlqService).list(eq(DeadLetterEntry.Status.OPEN), any(Pageable.class));
    }

    @Test
    void should_return_entry_when_getting_by_id() throws Exception {
        when(dlqService.findById("dlq-1")).thenReturn(sampleEntry);

        mockMvc.perform(get("/api/v1/dlq/dlq-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("dlq-1"))
                .andExpect(jsonPath("$.errorMessage").value("boom"));
    }

    @Test
    void should_return_400_when_getting_unknown_id() throws Exception {
        when(dlqService.findById("missing")).thenThrow(new IllegalArgumentException("DLQ entry not found: missing"));

        mockMvc.perform(get("/api/v1/dlq/missing"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_delegate_retry_to_service_and_return_entry() throws Exception {
        DeadLetterEntry retried = DeadLetterEntry.builder()
                .id("dlq-1")
                .workflowId("wf-1")
                .workflowRunId("run-1")
                .retryCount(1)
                .status(DeadLetterEntry.Status.RETRIED)
                .lastFailureAt(Instant.now())
                .build();

        when(dlqRetryService.retry("dlq-1")).thenReturn(retried);

        mockMvc.perform(post("/api/v1/dlq/dlq-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("dlq-1"))
                .andExpect(jsonPath("$.status").value("RETRIED"));

        verify(dlqRetryService).retry("dlq-1");
    }

    @Test
    void should_return_400_when_retrying_unknown_id() throws Exception {
        when(dlqRetryService.retry("missing"))
                .thenThrow(new IllegalArgumentException("DLQ entry not found: missing"));

        mockMvc.perform(post("/api/v1/dlq/missing/retry"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return_204_when_discarding_entry() throws Exception {
        doNothing().when(dlqService).discard("dlq-1");

        mockMvc.perform(delete("/api/v1/dlq/dlq-1"))
                .andExpect(status().isNoContent());

        verify(dlqService).discard("dlq-1");
    }

    @Test
    void should_return_400_when_discarding_unknown_id() throws Exception {
        doThrow(new IllegalArgumentException("not found")).when(dlqService).discard("missing");

        mockMvc.perform(delete("/api/v1/dlq/missing"))
                .andExpect(status().isBadRequest());
    }
}
