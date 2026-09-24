package com.automation.controller;

import com.automation.dto.response.ProjectResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.example.ExampleProjectService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(ProjectController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProjectService projectService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private WorkflowService workflowService;

    @MockitoBean
    private ExampleProjectService exampleProjectService;

    @MockitoBean
    private ProjectContextService projectContextService;

    @MockitoBean
    private ProjectOnboardingService projectOnboardingService;

    @MockitoBean
    private ProjectMapper projectMapper;

    @MockitoBean
    private WorkflowMapper workflowMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

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
                "proj-1", "Test Project", null, null, ProjectType.CODE, "/workspace/test",
                Map.of(), null, ProjectStatus.STOPPED, null,
                null, Instant.now(), Instant.now(), 0L
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
    void ensureExampleProject_firstCall_returns201WithTheProject() throws Exception {
        when(exampleProjectService.ensure("de"))
                .thenReturn(new ExampleProjectService.Result(testProject, true, 1));
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/example").param("locale", "de"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("proj-1"));
    }

    @Test
    void ensureExampleProject_existingProject_returns200() throws Exception {
        when(exampleProjectService.ensure(null))
                .thenReturn(new ExampleProjectService.Result(testProject, false, 0));
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/example"))
                .andExpect(status().isOk())
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
    void listArchivedProjects_returnsOk() throws Exception {
        ProjectResponse archivedResponse = new ProjectResponse(
                "proj-2", "Archived Project", null, null, ProjectType.CODE, "/workspace/archived",
                Map.of(), null, ProjectStatus.STOPPED, null,
                Instant.now(), Instant.now(), Instant.now(), 0L
        );
        when(projectService.findArchived()).thenReturn(List.of(testProject));
        when(projectMapper.toResponseList(any())).thenReturn(List.of(archivedResponse));

        mockMvc.perform(get("/api/v1/projects/archived"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("proj-2"))
                .andExpect(jsonPath("$[0].archivedAt").exists());
    }

    @Test
    void restoreProject_existingId_returnsOk() throws Exception {
        when(projectService.restore("proj-1")).thenReturn(testProject);
        when(projectMapper.toResponse(testProject)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/proj-1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("proj-1"));
    }

    @Test
    void restoreProject_nonExistent_returns404() throws Exception {
        when(projectService.restore("unknown"))
                .thenThrow(new ResourceNotFoundException("Project", "unknown"));

        mockMvc.perform(post("/api/v1/projects/unknown/restore"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startProject_returnsOk() throws Exception {
        testProject.setStatus(ProjectStatus.RUNNING);
        ProjectResponse runningResponse = new ProjectResponse(
                "proj-1", "Test Project", null, null, ProjectType.CODE, "/workspace/test",
                Map.of(), null, ProjectStatus.RUNNING, null,
                null, Instant.now(), Instant.now(), 0L
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
        when(workflowMapper.toResponseListWithRunCounts(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/proj-1/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
