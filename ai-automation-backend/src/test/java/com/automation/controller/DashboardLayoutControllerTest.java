package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.DashboardLayoutVersion;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.DashboardLayoutVersionMapperImpl;
import com.automation.repository.DashboardLayoutVersionListItem;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dashboard.DashboardLayoutVersionService;
import com.automation.service.dashboard.DashboardLayoutVersionService.SaveResult;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardLayoutController.class)
@Import({GlobalExceptionHandler.class, DashboardLayoutVersionMapperImpl.class})
@AutoConfigureMockMvc(addFilters = false)
class DashboardLayoutControllerTest {

    private static final Instant CREATED = Instant.parse("2026-09-15T10:00:00Z");
    private static final String CONTENT =
            "{\"activeViewId\":\"default\",\"views\":[{\"id\":\"default\",\"name\":\"\",\"widgets\":[]}]}";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DashboardLayoutVersionService service;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void lists_versions_without_layout_content() throws Exception {
        when(service.list(0, 20)).thenReturn(new PageImpl<>(List.of(item("version-1")), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/dashboard/layouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("version-1"))
                .andExpect(jsonPath("$.content[0].deviceLabel").value("Laptop"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-09-15T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].viewCount").value(1))
                .andExpect(jsonPath("$.content[0].layout").doesNotExist())
                .andExpect(jsonPath("$.content[0].content").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void returns_one_version_with_its_layout() throws Exception {
        when(service.get("version-1")).thenReturn(entity("version-1"));

        mockMvc.perform(get("/api/v1/dashboard/layouts/version-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("version-1"))
                .andExpect(jsonPath("$.layout.activeViewId").value("default"))
                .andExpect(jsonPath("$.layout.views[0].id").value("default"));
    }

    @Test
    void unknown_version_is_404() throws Exception {
        when(service.get("missing")).thenThrow(new ResourceNotFoundException("DashboardLayoutVersion", "missing"));

        mockMvc.perform(get("/api/v1/dashboard/layouts/missing")).andExpect(status().isNotFound());
    }

    @Test
    void save_answers_201_for_a_new_version_and_200_for_a_duplicate() throws Exception {
        when(service.save(any())).thenReturn(new SaveResult(entity("version-2"), true));
        mockMvc.perform(post("/api/v1/dashboard/layouts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("version-2"));

        when(service.save(any())).thenReturn(new SaveResult(entity("version-1"), false));
        mockMvc.perform(post("/api/v1/dashboard/layouts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("version-1"));
    }

    @Test
    void save_maps_a_stale_base_to_409_and_invalid_input_to_400() throws Exception {
        when(service.save(any())).thenThrow(new IllegalStateException("changed on another device"));
        mockMvc.perform(post("/api/v1/dashboard/layouts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // doThrow(...).when(...), not when(...).thenThrow(...): restubbing the same mocked call via
        // when() would itself invoke the mock and replay the still-registered first thenThrow(),
        // erroring out before the new stub attaches (classic Mockito re-stubbing gotcha).
        doThrow(new IllegalArgumentException("Invalid dashboard layout: x")).when(service).save(any());
        mockMvc.perform(post("/api/v1/dashboard/layouts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void rename_returns_the_updated_summary() throws Exception {
        DashboardLayoutVersion renamed = entity("version-1");
        renamed.setName("vor dem Umbau");
        when(service.rename(eq("version-1"), any())).thenReturn(renamed);

        mockMvc.perform(patch("/api/v1/dashboard/layouts/version-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"vor dem Umbau\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("vor dem Umbau"));
    }

    private static DashboardLayoutVersion entity(String id) {
        return DashboardLayoutVersion.builder().id(id).createdAt(CREATED).deviceLabel("Laptop")
                .formatVersion(2).viewCount(1).contentHash("abc").content(CONTENT).build();
    }

    private static DashboardLayoutVersionListItem item(String id) {
        return new DashboardLayoutVersionListItem() {
            @Override public String getId() { return id; }
            @Override public Instant getCreatedAt() { return CREATED; }
            @Override public String getDeviceLabel() { return "Laptop"; }
            @Override public String getName() { return null; }
            @Override public int getFormatVersion() { return 2; }
            @Override public int getViewCount() { return 1; }
            @Override public String getContentHash() { return "abc"; }
        };
    }
}
