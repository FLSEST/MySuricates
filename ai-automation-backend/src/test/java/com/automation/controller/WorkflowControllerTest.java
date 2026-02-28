package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.*;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowOptimizationService;
import com.automation.service.system.ApiKeyService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkflowController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowService workflowService;

    @MockBean
    private WorkflowExecutor workflowExecutor;

    @MockBean
    private WorkflowRunRepository runRepository;

    @MockBean
    private WorkflowMapper workflowMapper;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private WorkflowOptimizationService optimizationService;

    @MockBean
    private ProjectRepository projectRepository;

    private Workflow testWorkflow;
    private WorkflowResponse testResponse;

    @BeforeEach
    void setUp() {
        Project project = Project.builder()
                .id("proj-1").name("Test").type(ProjectType.CODE).path("/test").build();

        testWorkflow = Workflow.builder()
                .id("wf-1")
                .project(project)
                .name("Test Workflow")
                .description("A test workflow")
                .build();

        testResponse = new WorkflowResponse(
                "wf-1", "proj-1", "Test Project", null, null,
                "Test Workflow", "A test workflow",
                null, List.of(), "1.0.0", Map.of(), List.of(), 0L,
                null, null, null, Map.of(), null, null, Instant.now(), Instant.now()
        );
    }

    @Test
    void listWorkflows_returnsOk() throws Exception {
        when(workflowService.findAll()).thenReturn(List.of(testWorkflow));
        when(workflowMapper.toResponseList(any())).thenReturn(List.of(testResponse));

        mockMvc.perform(get("/api/v1/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("wf-1"));
    }

    @Test
    void getWorkflow_existingId_returnsOk() throws Exception {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        mockMvc.perform(get("/api/v1/workflows/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-1"))
                .andExpect(jsonPath("$.name").value("Test Workflow"));
    }

    @Test
    void getWorkflow_nonExistent_returns404() throws Exception {
        when(workflowService.findById("unknown"))
                .thenThrow(new ResourceNotFoundException("Workflow", "unknown"));

        mockMvc.perform(get("/api/v1/workflows/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWorkflow_validRequest_returns201() throws Exception {
        when(workflowService.create(any())).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        String body = """
                {
                    "projectId": "proj-1",
                    "name": "Test Workflow",
                    "blocks": [
                        {"type": "simple", "config": {"command": "echo test"}}
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("wf-1"));
    }

    @Test
    void createWorkflow_missingProjectId_returns400() throws Exception {
        String body = """
                {
                    "name": "Test Workflow",
                    "blocks": []
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWorkflow_missingName_returns400() throws Exception {
        String body = """
                {
                    "projectId": "proj-1",
                    "blocks": []
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateWorkflow_validRequest_returnsOk() throws Exception {
        when(workflowService.update(eq("wf-1"), any())).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        String body = """
                {
                    "name": "Updated Workflow"
                }
                """;

        mockMvc.perform(put("/api/v1/workflows/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void deleteWorkflow_existingId_returns204() throws Exception {
        doNothing().when(workflowService).delete("wf-1");

        mockMvc.perform(delete("/api/v1/workflows/wf-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void executeWorkflow_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .workflow(testWorkflow)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .build();

        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", "Test Project", null, RunStatus.RUNNING, List.of(),
                Instant.now(), null, null, null, null, Map.of(), Map.of()
        );

        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(workflowExecutor.execute(eq(testWorkflow), any())).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        String body = """
                {
                    "context": {"branch": "main"}
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/wf-1/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void executeWorkflow_withoutBody_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").workflow(testWorkflow).status(RunStatus.RUNNING).build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", "Test Project", null, RunStatus.RUNNING, List.of(),
                null, null, null, null, null, null, null
        );

        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(workflowExecutor.execute(eq(testWorkflow), any())).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        mockMvc.perform(post("/api/v1/workflows/wf-1/execute")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getWorkflowRuns_returnsOk() throws Exception {
        Page<WorkflowRun> emptyPage = new PageImpl<>(List.of());
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(Pageable.class))).thenReturn(emptyPage);
        when(workflowMapper.toRunResponseList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflows/wf-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getWorkflowRun_existingId_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").workflow(testWorkflow).status(RunStatus.COMPLETED).build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", "Test Project", null, RunStatus.COMPLETED, List.of(),
                null, null, null, null, null, null, null
        );

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        mockMvc.perform(get("/api/v1/workflows/wf-1/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"));
    }

    @Test
    void activateSkills_returnsOk() throws Exception {
        when(workflowService.activateSkills(eq("wf-1"), any())).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        String body = """
                {
                    "skillIds": ["skill-1", "skill-2"]
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/wf-1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void deactivateSkill_returnsOk() throws Exception {
        when(workflowService.deactivateSkill("wf-1", "skill-1")).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        mockMvc.perform(delete("/api/v1/workflows/wf-1/skills/skill-1"))
                .andExpect(status().isOk());
    }
}
