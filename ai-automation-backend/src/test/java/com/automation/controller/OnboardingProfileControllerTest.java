package com.automation.controller;

import com.automation.dto.response.OnboardingProfileResponse;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.OnboardingProfileMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.OnboardingProfileService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OnboardingProfileController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class OnboardingProfileControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private OnboardingProfileService profileService;
    @MockitoBean private OnboardingProfileMapper profileMapper;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private OnboardingProfile buildProfile(String id, String key) {
        return OnboardingProfile.builder()
                .id(id).key(key)
                .name(Map.of("de", "Test"))
                .content(Map.of("de", "content"))
                .type(OnboardingProfileType.PROFILE)
                .builtIn(false).sortOrder(0).version(1)
                .build();
    }

    private OnboardingProfileResponse buildResponse(String id, String key) {
        return new OnboardingProfileResponse(id, key, Map.of("de", "Test"), Map.of(),
                Map.of("de", "content"), OnboardingProfileType.PROFILE, false, 0, 1, 0,
                false,
                false,
                Instant.now(), Instant.now());
    }

    @Test
    void listProfiles_returnsOk() throws Exception {
        OnboardingProfile profile = buildProfile("p1", "base");
        when(profileService.findAll()).thenReturn(List.of(profile));
        when(profileMapper.toResponse(profile)).thenReturn(buildResponse("p1", "base"));
        when(profileService.getHistoryCount("p1")).thenReturn(0);

        mockMvc.perform(get("/api/v1/onboarding-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("base"));
    }

    @Test
    void getProfile_existing_returnsOk() throws Exception {
        OnboardingProfile profile = buildProfile("p1", "base");
        when(profileService.findById("p1")).thenReturn(profile);
        when(profileMapper.toResponse(profile)).thenReturn(buildResponse("p1", "base"));
        when(profileService.getHistoryCount("p1")).thenReturn(0);

        mockMvc.perform(get("/api/v1/onboarding-profiles/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("base"));
    }

    @Test
    void getProfile_notFound_returns404() throws Exception {
        when(profileService.findById("missing"))
                .thenThrow(new ResourceNotFoundException("OnboardingProfile", "missing"));

        mockMvc.perform(get("/api/v1/onboarding-profiles/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createProfile_returns201() throws Exception {
        OnboardingProfile created = buildProfile("p-new", "new-key");
        when(profileService.create(any())).thenReturn(created);
        when(profileMapper.toResponse(created)).thenReturn(buildResponse("p-new", "new-key"));
        when(profileService.getHistoryCount("p-new")).thenReturn(0);

        String body = """
                {"key": "new-key", "name": {"de": "Neu"}, "content": {"de": "test content"}, "type": "PROFILE"}
                """;

        mockMvc.perform(post("/api/v1/onboarding-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("new-key"));
    }

    @Test
    void updateProfile_returnsOk() throws Exception {
        OnboardingProfile updated = buildProfile("p1", "base");
        when(profileService.update(eq("p1"), any())).thenReturn(updated);
        when(profileMapper.toResponse(updated)).thenReturn(buildResponse("p1", "base"));
        when(profileService.getHistoryCount("p1")).thenReturn(0);

        String body = """
                {"name": {"de": "Aktualisiert"}, "content": {"de": "new content"}}
                """;

        mockMvc.perform(put("/api/v1/onboarding-profiles/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void deleteProfile_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/onboarding-profiles/p1"))
                .andExpect(status().isNoContent());

        verify(profileService).delete("p1");
    }

    @Test
    void resetProfile_returnsOk() throws Exception {
        OnboardingProfile reset = buildProfile("p1", "base");
        when(profileService.resetToDefault("p1")).thenReturn(reset);
        when(profileMapper.toResponse(reset)).thenReturn(buildResponse("p1", "base"));
        when(profileService.getHistoryCount("p1")).thenReturn(0);

        mockMvc.perform(post("/api/v1/onboarding-profiles/p1/reset"))
                .andExpect(status().isOk());
    }

    @Test
    void getHistory_returnsOk() throws Exception {
        when(profileService.getHistory("p1")).thenReturn(List.of());
        when(profileMapper.toHistoryResponseList(List.of())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/onboarding-profiles/p1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
