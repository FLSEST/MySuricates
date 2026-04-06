package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.request.WishRespondRequest;
import com.automation.dto.response.WishResponse;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WishMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.wish.WishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WishController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WishControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private WishService wishService;
    @MockBean private WishMapper wishMapper;
    @MockBean private ApiKeyService apiKeyService;
    @MockBean private ActivityLogService activityLogService;
    @MockBean private RateLimitConfig rateLimitConfig;

    private WishResponse sampleResponse() {
        return new WishResponse("w-1", "Build a dashboard", WishStatus.DECOMPOSING,
                "proj-1", "Project", null, List.of(), List.of(), List.of(), List.of(),
                null, 0, 3, null, null, null, List.of(), Map.of(),
                Instant.now(), Instant.now());
    }

    @Test
    void createWish_validRequest_returns201() throws Exception {
        var wish = Wish.builder().id("w-1").build();
        when(wishService.create(any(), any(), any(), any())).thenReturn(wish);
        when(wishMapper.toResponse(wish)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Build a dashboard", "projectId": "proj-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("w-1"));

        verify(wishService).fulfillAsync("w-1");
    }

    @Test
    void createWish_missingText_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/wishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "", "projectId": "proj-1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWishes_returnsOk() throws Exception {
        when(wishService.findAll()).thenReturn(List.of());
        when(wishMapper.toResponseList(any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/wishes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("w-1"));
    }

    @Test
    void getWish_existing_returnsOk() throws Exception {
        when(wishMapper.toResponse(any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/wishes/w-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").value("Build a dashboard"));
    }

    @Test
    void getWish_nonExistent_returns404() throws Exception {
        when(wishService.findById("unknown")).thenThrow(new ResourceNotFoundException("Wish", "unknown"));

        mockMvc.perform(get("/api/v1/wishes/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelWish_returnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/wishes/w-1/cancel"))
                .andExpect(status().isOk());

        verify(wishService).cancel("w-1");
    }

    @Test
    void respondToWish_accept_returnsOk() throws Exception {
        var wish = Wish.builder().id("w-1").build();
        when(wishService.respond(eq("w-1"), any())).thenReturn(wish);
        when(wishMapper.toResponse(wish)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/wishes/w-1/respond")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "ACCEPT", "feedback": null}
                                """))
                .andExpect(status().isOk());

        verify(wishService).respond(eq("w-1"), any(WishRespondRequest.class));
    }

    @Test
    void crystallizeWish_returnsTemplateId() throws Exception {
        when(wishService.crystallize("w-1")).thenReturn("template-42");

        mockMvc.perform(post("/api/v1/wishes/w-1/crystallize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value("template-42"));
    }

    @Test
    void deleteWish_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/wishes/w-1"))
                .andExpect(status().isNoContent());

        verify(wishService).delete("w-1");
    }
}
