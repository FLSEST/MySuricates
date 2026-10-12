package com.automation.integration;

import com.automation.entity.*;
import com.automation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests that verify controller endpoints correctly handle lazy-loaded
 * entity associations when OSIV is disabled. These tests catch regressions where
 * {@code @Transactional(readOnly = true)} is accidentally removed from controller
 * methods that map entities to DTOs via MapStruct.
 *
 * <p>Unit tests with {@code @MockitoBean} do not catch these issues because the mapper
 * is mocked and never accesses real lazy associations.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class LazyLoadingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;

    private Project testProject;
    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.save(Project.builder()
                .name("Lazy Test Project")
                .type(ProjectType.CODE)
                .path("/tmp")
                .status(ProjectStatus.STOPPED)
                .build());

        testWorkflow = workflowRepository.save(Workflow.builder()
                .name("Lazy Test Workflow")
                .project(testProject)
                .build());
    }

    @Test
    void listExecutions_resolvesLazyWorkflowAssociation() throws Exception {
        runRepository.save(WorkflowRun.builder()
                .workflow(testWorkflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .inputContext(Map.of())
                .metadata(Map.of())
                .build());

        mockMvc.perform(get("/api/v1/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].workflowName").value("Lazy Test Workflow"));
    }

    @Test
    void getExecution_resolvesLazyWorkflowAssociation() throws Exception {
        var run = runRepository.save(WorkflowRun.builder()
                .workflow(testWorkflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .inputContext(Map.of())
                .metadata(Map.of())
                .build());

        mockMvc.perform(get("/api/v1/executions/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowName").value("Lazy Test Workflow"))
                .andExpect(jsonPath("$.projectName").value("Lazy Test Project"));
    }

    @Test
    void listExecutions_withStatusFilter_resolvesLazyAssociation() throws Exception {
        runRepository.save(WorkflowRun.builder()
                .workflow(testWorkflow)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .inputContext(Map.of())
                .metadata(Map.of())
                .build());

        mockMvc.perform(get("/api/v1/executions").param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].workflowName").value("Lazy Test Workflow"));
    }

    @Test
    void scheduleRuns_resolvesLazyWorkflowAssociation() throws Exception {
        var schedule = scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                .workflow(testWorkflow)
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                .enabled(true)
                .inputContext(Map.of())
                .build());

        runRepository.save(WorkflowRun.builder()
                .workflow(testWorkflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .triggeredByScheduleId(schedule.getId())
                .inputContext(Map.of())
                .metadata(Map.of())
                .build());

        mockMvc.perform(get("/api/v1/schedules/" + schedule.getId() + "/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRuns", hasSize(1)))
                .andExpect(jsonPath("$.workflowRuns[0].workflowName").value("Lazy Test Workflow"));
    }
}
