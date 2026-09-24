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
import org.springframework.transaction.annotation.Propagation;
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

    // NOT_SUPPORTED suspends the class-level @Transactional for this method: /system/health does its
    // own dataSource.getConnection() (SELECT 1). With pool-size=1, a held test transaction would starve
    // that second acquisition (→ DB DOWN after the 120s connection-timeout). Running without a tx mirrors
    // prod, where this endpoint has no ambient transaction. See CLAUDE.md → "HikariCP pool-size = 1".
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

    // TODO c8f122ac: unknown paths used to fall into GlobalExceptionHandler's generic
    // Exception.class catch-all (NoResourceFoundException wasn't handled explicitly), answering
    // 500 INTERNAL_ERROR with a logged stack trace instead of a routine 404.
    @Test
    void unknownApiPath_returns404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/projects/x/container/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
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

    // TODO 4e7a7401: an unknown filter value failed inside the query — Spring Data wrapped the
    // IllegalArgumentException of the enum parse — and answered 500 INTERNAL_ERROR.
    @Test
    void todoList_unknownFilterValue_returns400NamingTheAllowedValues() throws Exception {
        mockMvc.perform(get("/api/v1/todos").param("status", "OPEN").param("status", "BLOCKED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error").value(allOf(
                        containsString("OPEN, IN_PROGRESS, DONE, BACKLOG oder ARCHIVED"), containsString("'BLOCKED'"))));
        mockMvc.perform(get("/api/v1/todos").param("priority", "URGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("LOW, MEDIUM, HIGH oder CRITICAL")));
        mockMvc.perform(get("/api/v1/todos").param("source", "HUMAN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("USER, AI oder WORKFLOW")));
    }

    // Spot check from 4e7a7401: the run list parses its status before the query, so it already says 400.
    @Test
    void executionList_unknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/executions").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
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
