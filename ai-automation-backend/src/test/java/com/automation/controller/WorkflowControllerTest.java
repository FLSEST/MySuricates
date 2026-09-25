package com.automation.controller;

import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.*;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.export.ProcessFormatAdapter;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowExecutor;
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

@WebMvcTest(WorkflowController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkflowService workflowService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private WorkflowExecutor workflowExecutor;

    @MockitoBean
    private ExecutionQueryService executionQueryService;

    @MockitoBean
    private WorkflowMapper workflowMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @MockitoBean
    private ProcessFormatAdapter bpmnExportAdapter;

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
                null, List.of(), "1.0.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, Instant.now(), Instant.now(), null
        );
    }

    @Test
    void listWorkflows_returnsOk() throws Exception {
        when(workflowService.findAll()).thenReturn(List.of(testWorkflow));
        when(workflowMapper.toResponseListWithRunCounts(any())).thenReturn(List.of(testResponse));

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
        when(workflowService.create(any(), eq(ChangeSource.USER))).thenReturn(testWorkflow);
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
    void createWorkflow_unknownBlockType_returns400WithAllowedTypes() throws Exception {
        when(workflowService.create(any(), eq(ChangeSource.USER)))
                .thenThrow(new IllegalArgumentException("Block 'weird' has unknown type 'frobnicate' "
                        + "— allowed types: agentic, approval, shell, simple"));

        String body = """
                {
                    "projectId": "proj-1",
                    "name": "Test Workflow",
                    "blocks": [
                        {"type": "frobnicate", "name": "weird", "config": {}}
                    ]
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("frobnicate")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("allowed types")));
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
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.USER))).thenReturn(testWorkflow);
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
    void updateWorkflow_bindsTheVersionLabel() throws Exception {
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.USER))).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        mockMvc.perform(put("/api/v1/workflows/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"4.4.0\", \"effort\": \"high\"}"))
                .andExpect(status().isOk());

        verify(workflowService).update(eq("wf-1"),
                argThat(req -> "4.4.0".equals(req.version()) && "high".equals(req.effort()) && req.name() == null),
                eq(ChangeSource.USER));
    }

    // --- import: the version label travels with the export file (TODO 5e2e73a5) ------------

    @Test
    void importWorkflow_updateIfExists_takesOverTheVersionLabel() throws Exception {
        when(workflowService.findByNameAndProjectId("Test Workflow", "proj-1")).thenReturn(java.util.Optional.of(testWorkflow));
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.IMPORT))).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/workflows/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": \"proj-1\", \"name\": \"Test Workflow\", \"version\": \"4.4.0\","
                                + " \"updateIfExists\": true, \"blocks\": []}"))
                .andExpect(status().isOk());

        verify(workflowService).update(eq("wf-1"),
                argThat(req -> "4.4.0".equals(req.version()) && "Imported workflow update".equals(req.changeMessage())),
                eq(ChangeSource.IMPORT));
    }

    @Test
    void importWorkflow_updateIfExists_withoutOrWithBlankVersion_keepsTheLabel() throws Exception {
        when(workflowService.findByNameAndProjectId("Test Workflow", "proj-1")).thenReturn(java.util.Optional.of(testWorkflow));
        when(workflowService.update(eq("wf-1"), any(), eq(ChangeSource.IMPORT))).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        for (String body : List.of(
                "{\"projectId\": \"proj-1\", \"name\": \"Test Workflow\", \"updateIfExists\": true}",
                "{\"projectId\": \"proj-1\", \"name\": \"Test Workflow\", \"version\": \"  \", \"updateIfExists\": true}")) {
            mockMvc.perform(post("/api/v1/workflows/import").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        verify(workflowService, times(2)).update(eq("wf-1"), argThat(req -> req.version() == null), eq(ChangeSource.IMPORT));
    }

    @Test
    void importWorkflow_asNewWorkflow_takesOverTheVersionLabel() throws Exception {
        when(workflowService.create(any(), eq(ChangeSource.IMPORT))).thenReturn(testWorkflow);
        when(workflowMapper.toResponse(testWorkflow)).thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/workflows/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": \"proj-1\", \"name\": \"Neu\", \"version\": \"4.4.0\"}"))
                .andExpect(status().isCreated());

        verify(workflowService).create(argThat(req -> "4.4.0".equals(req.version()) && "Neu".equals(req.name())),
                eq(ChangeSource.IMPORT));
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
                Instant.now(), null, null, null, null, Map.of(), Map.of(), null
        );

        when(workflowService.findByIdEager("wf-1")).thenReturn(testWorkflow);
        when(workflowExecutor.execute(eq(testWorkflow), any(), eq(TriggerSource.MANUAL))).thenReturn(run);
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
                null, null, null, null, null, null, null, null
        );

        when(workflowService.findByIdEager("wf-1")).thenReturn(testWorkflow);
        when(workflowExecutor.execute(eq(testWorkflow), any(), eq(TriggerSource.MANUAL))).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        mockMvc.perform(post("/api/v1/workflows/wf-1/execute")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void getWorkflowRuns_returnsOk() throws Exception {
        when(executionQueryService.listRunsForWorkflow("wf-1", 0, 50))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0L, 0));

        mockMvc.perform(get("/api/v1/workflows/wf-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getWorkflowRun_existingId_returnsOk() throws Exception {
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", "Test Project", null, RunStatus.COMPLETED, List.of(),
                null, null, null, null, null, null, null, null
        );

        when(executionQueryService.getRunForWorkflow("run-1")).thenReturn(runResponse);

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
