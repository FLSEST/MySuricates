package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for REST controllers with real database.
 * Security is disabled via application-integration.yml.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class ControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;

    @Test
    void healthEndpoint_returnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void projectCrud_createReadList() throws Exception {
        // Create temp directory for project path validation
        Path tempDir = Files.createTempDirectory("api-test");
        tempDir.toFile().deleteOnExit();

        // Create
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "API Test Project",
                "type", "CODE",
                "path", tempDir.toString()
        ));
        String response = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("API Test Project"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String projectId = objectMapper.readTree(response).get("id").asText();

        // Read
        mockMvc.perform(get("/api/v1/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API Test Project"));

        // List
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void projectNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/projects/non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void todoCrud_createAndUpdateStatus() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "title", "Integration Test Todo",
                "priority", "MEDIUM",
                "source", "AI",
                "sourceAgent", "integration-test"
        ));
        String response = mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Integration Test Todo"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();

        String todoId = objectMapper.readTree(response).get("id").asText();

        // Update status
        mockMvc.perform(patch("/api/v1/todos/" + todoId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void todoValidation_missingTitle_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "priority", "MEDIUM"
        ));
        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void workflowTemplates_listReturnsBuiltIns() throws Exception {
        mockMvc.perform(get("/api/v1/workflow-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }
}
