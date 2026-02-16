package com.automation.controller;

import com.automation.dto.response.ProjectResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProjectController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProjectService projectService;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private ProjectMapper projectMapper;

    @MockBean
    private WorkflowMapper workflowMapper;

    private Project testProject;
    private ProjectResponse testResponse;

    @BeforeEach
    void setUp() {
        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/workspace/test")
                .status(ProjectStatus.STOPPED)
                .build();

        testResponse = new ProjectResponse(
                "proj-1", "Test Project", ProjectType.CODE, "/workspace/test",
                Map.of(), ProjectStatus.STOPPED, null,
                Instant.now(), Instant.now()
        );
    }

    @Test
    void listProjects_returnsOk() throws Exception {
        when(projectService.findAll()).thenReturn(List.of(testProject));
        when(projectMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("proj-1"))
                .andExpect(jsonPath("$[0].name").value("Test Project"));
    }

    @Test
    void getProject_existingId_returnsOk() throws Exception {
        when(projectService.findById("proj-1")).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        mockMvc.perform(get("/api/v1/projects/proj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("proj-1"))
                .andExpect(jsonPath("$.name").value("Test Project"));
    }

    @Test
    void getProject_nonExistent_returns404() throws Exception {
        when(projectService.findById("unknown"))
                .thenThrow(new ResourceNotFoundException("Project", "unknown"));

        mockMvc.perform(get("/api/v1/projects/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createProject_validRequest_returns201() throws Exception {
        when(projectService.create(any())).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        String body = """
                {
                    "name": "Test Project",
                    "type": "CODE",
                    "path": "/workspace/test"
                }
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("proj-1"));
    }

    @Test
    void createProject_missingName_returns400() throws Exception {
        String body = """
                {
                    "type": "CODE",
                    "path": "/workspace/test"
                }
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void createProject_missingType_returns400() throws Exception {
        String body = """
                {
                    "name": "Test",
                    "path": "/workspace/test"
                }
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateProject_validRequest_returnsOk() throws Exception {
        when(projectService.update(eq("proj-1"), any())).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        String body = """
                {
                    "name": "Updated Name"
                }
                """;

        mockMvc.perform(put("/api/v1/projects/proj-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("proj-1"));
    }

    @Test
    void deleteProject_existingId_returns204() throws Exception {
        doNothing().when(projectService).delete("proj-1");

        mockMvc.perform(delete("/api/v1/projects/proj-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteProject_nonExistent_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Project", "unknown"))
                .when(projectService).delete("unknown");

        mockMvc.perform(delete("/api/v1/projects/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startProject_returnsOk() throws Exception {
        testProject.setStatus(ProjectStatus.RUNNING);
        ProjectResponse runningResponse = new ProjectResponse(
                "proj-1", "Test Project", ProjectType.CODE, "/workspace/test",
                Map.of(), ProjectStatus.RUNNING, null,
                Instant.now(), Instant.now()
        );
        when(projectService.startContainer("proj-1")).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(runningResponse);

        mockMvc.perform(post("/api/v1/projects/proj-1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void stopProject_returnsOk() throws Exception {
        when(projectService.stopContainer("proj-1")).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/proj-1/stop"))
                .andExpect(status().isOk());
    }

    @Test
    void getProjectWorkflows_returnsOk() throws Exception {
        when(workflowService.findByProjectId("proj-1")).thenReturn(List.of());
        when(workflowMapper.toResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/proj-1/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
