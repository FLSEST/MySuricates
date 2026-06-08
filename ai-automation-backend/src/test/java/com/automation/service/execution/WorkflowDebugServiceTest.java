package com.automation.service.execution;

import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowDebugServiceTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @InjectMocks
    private WorkflowDebugService service;

    @Test
    void getBrokenWorkflows_mapsErrorMapToOverviewFields() {
        Project project = Project.builder().id("p1").name("Alpha").build();
        Workflow wf = Workflow.builder().id("wf1").name("Build").project(project).build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run1").workflow(wf).workflowName("Build")
                .status(RunStatus.FAILED)
                .startedAt(Instant.parse("2026-06-01T10:00:00Z"))
                .completedAt(Instant.parse("2026-06-01T10:01:00Z"))
                .durationMs(60_000L)
                // WorkflowExecutor writes the block-failure error as {"step","blockType","message"}.
                .error(Map.of("step", 2, "blockType", "shell", "message", "boom"))
                .build();
        when(workflowRunRepository.findBrokenWorkflows(WorkflowDebugService.BROKEN_STATUSES))
                .thenReturn(List.of(run));

        List<BrokenWorkflowResponse> result = service.getBrokenWorkflows();

        assertThat(result).hasSize(1);
        BrokenWorkflowResponse r = result.get(0);
        assertThat(r.workflowId()).isEqualTo("wf1");
        assertThat(r.workflowName()).isEqualTo("Build");
        assertThat(r.projectName()).isEqualTo("Alpha");
        assertThat(r.runId()).isEqualTo("run1");
        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.errorMessage()).isEqualTo("boom");
        assertThat(r.failedStepIndex()).isEqualTo(2);
        assertThat(r.failedBlockType()).isEqualTo("shell");
        assertThat(r.durationMs()).isEqualTo(60_000L);
    }

    @Test
    void getBrokenWorkflows_handlesErrorMapWithoutStepOrBlockType() {
        Workflow wf = Workflow.builder().id("wf1").name("Build").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run1").workflow(wf).workflowName("Build")
                .status(RunStatus.COMPLETED_WITH_ERRORS)
                .error(Map.of("message", "partial failure"))
                .build();
        when(workflowRunRepository.findBrokenWorkflows(WorkflowDebugService.BROKEN_STATUSES))
                .thenReturn(List.of(run));

        List<BrokenWorkflowResponse> result = service.getBrokenWorkflows();

        assertThat(result).hasSize(1);
        BrokenWorkflowResponse r = result.get(0);
        assertThat(r.errorMessage()).isEqualTo("partial failure");
        assertThat(r.failedStepIndex()).isNull();
        assertThat(r.failedBlockType()).isNull();
        assertThat(r.projectName()).isNull();
        assertThat(r.status()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void getBrokenWorkflows_emptyWhenNothingBroken() {
        when(workflowRunRepository.findBrokenWorkflows(WorkflowDebugService.BROKEN_STATUSES))
                .thenReturn(List.of());

        assertThat(service.getBrokenWorkflows()).isEmpty();
    }
}
