package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.PageSuggestionService;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageSuggestionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PageSuggestionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PageSuggestionService pageSuggestionService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void getSuggestions_returnsListForPage() throws Exception {
        when(pageSuggestionService.getSuggestions("dashboard"))
                .thenReturn(List.of("What happened today?", "Show active workflows"));

        mockMvc.perform(get("/api/v1/assistant/suggestions")
                        .param("page", "dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("What happened today?"))
                .andExpect(jsonPath("$[1]").value("Show active workflows"));

        verify(pageSuggestionService).getSuggestions("dashboard");
    }

    @Test
    void getSuggestions_returnsEmptyListWhenNoSuggestions() throws Exception {
        when(pageSuggestionService.getSuggestions("unknown"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/assistant/suggestions")
                        .param("page", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getSuggestions_missingPageParam_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/suggestions"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshSuggestions_returnsRefreshedList() throws Exception {
        when(pageSuggestionService.refreshSuggestions("runs"))
                .thenReturn(List.of("What failed?", "Show success rate"));

        mockMvc.perform(post("/api/v1/assistant/suggestions/refresh")
                        .param("page", "runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("What failed?"))
                .andExpect(jsonPath("$[1]").value("Show success rate"));

        verify(pageSuggestionService).refreshSuggestions("runs");
    }

    @Test
    void refreshSuggestions_missingPageParam_returnsError() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggestions/refresh"))
                .andExpect(status().isBadRequest());
    }
}
