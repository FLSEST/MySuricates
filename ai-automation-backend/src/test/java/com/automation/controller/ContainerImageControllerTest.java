package com.automation.controller;

import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.containerimage.AgentImageCatalog;
import com.automation.service.containerimage.ContainerImageOverviewService;
import com.automation.service.containerimage.ContainerImageOverviewService.ImageView;
import com.automation.service.containerimage.ContainerImageOverviewService.Overview;
import com.automation.service.containerimage.ContainerImageOverviewService.Status;
import com.automation.service.containerimage.ContainerImageUsage;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContainerImageController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ContainerImageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContainerImageOverviewService overviewService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void overviewIsServedAsJson() throws Exception {
        ImageView devops = new ImageView("automation-agent-devops:latest", true, Map.of("de", "Werkzeuge", "en", "Tools"),
                "ubuntu:24.04", List.of(new AgentImageCatalog.Tool("trivy", "0.74.0", "release", "TRIVY_VERSION")),
                Status.OUTDATED, "2026-09-23T08:00:00Z", true, "scripts/build-agent-images.sh agent-devops",
                List.of(new ContainerImageUsage.WorkflowUse("w1", "Security", "trivy", ContainerImageUsage.Mode.TOOL)),
                List.of());
        when(overviewService.overview()).thenReturn(new Overview("CONNECTED", false, List.of(devops)));

        mockMvc.perform(get("/api/v1/container-images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docker").value("CONNECTED"))
                .andExpect(jsonPath("$.allowlistActive").value(false))
                .andExpect(jsonPath("$.images[0].status").value("OUTDATED"))
                .andExpect(jsonPath("$.images[0].tools[0].version").value("0.74.0"))
                .andExpect(jsonPath("$.images[0].workflows[0].mode").value("TOOL"))
                .andExpect(jsonPath("$.images[0].buildCommand").value("scripts/build-agent-images.sh agent-devops"));
    }

    @Test
    void thereIsNoBuildEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/container-images"))
                .andExpect(status().is4xxClientError());
    }
}
