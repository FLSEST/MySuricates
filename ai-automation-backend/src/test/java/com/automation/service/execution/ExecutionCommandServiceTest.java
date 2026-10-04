package com.automation.service.execution;

import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.testsupport.FakeTransactions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionCommandServiceTest {

    @Mock private ExecutionQueryService executionQueryService;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowMapper workflowMapper;
    @InjectMocks private ExecutionCommandService service;

    @Test
    void retry_executesEagerlyLoadedWorkflowWithOriginalInputContext() {
        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflow(workflow).status(RunStatus.FAILED)
                .inputContext(Map.of("key", "val")).build();
        WorkflowRun newRun = WorkflowRun.builder().id("run-2").status(RunStatus.RUNNING).build();
        WorkflowRunResponse response = mock(WorkflowRunResponse.class);
        when(executionQueryService.findById("run-1")).thenReturn(run);
        when(workflowService.findByIdEager("wf-1")).thenReturn(workflow);
        when(workflowExecutor.execute(workflow, Map.of("key", "val"), TriggerSource.RETRY)).thenReturn(newRun);
        when(workflowMapper.toRunResponse(newRun)).thenReturn(response);

        assertThat(service.retry("run-1")).isSameAs(response);
    }

    /**
     * fe06ebf9: the new run's thread must not start inside the request transaction — it would run
     * before the run row is committed (and wait for the single pooled connection meanwhile).
     */
    @Test
    void retry_startsTheRunOutsideATransaction() {
        ExecutionCommandService proxied = FakeTransactions.proxy(service, FakeTransactions.manager());
        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflow(workflow).status(RunStatus.FAILED).build();
        WorkflowRun newRun = WorkflowRun.builder().id("run-2").status(RunStatus.RUNNING).build();
        AtomicBoolean startedInTransaction = new AtomicBoolean(true);
        when(executionQueryService.findById("run-1")).thenReturn(run);
        when(workflowService.findByIdEager("wf-1")).thenReturn(workflow);
        when(workflowExecutor.execute(eq(workflow), any(), eq(TriggerSource.RETRY))).thenAnswer(inv -> {
            startedInTransaction.set(FakeTransactions.active());
            return newRun;
        });

        proxied.retry("run-1");

        assertThat(startedInTransaction).as("execute() ran inside a transaction").isFalse();
    }

    @Test
    void retry_unknownRun_propagatesNotFound() {
        when(executionQueryService.findById("unknown")).thenThrow(new ResourceNotFoundException("WorkflowRun", "unknown"));

        assertThatThrownBy(() -> service.retry("unknown")).isInstanceOf(ResourceNotFoundException.class);
        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.RETRY));
    }

    @Test
    void resumeFromStep_delegatesToExecutorAndReadsTheResponseAfresh() {
        WorkflowRun resumed = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
        WorkflowRunResponse response = mock(WorkflowRunResponse.class);
        when(workflowExecutor.resumeFromStep("run-1", TriggerSource.RESUME)).thenReturn(resumed);
        when(executionQueryService.getExecutionResponse("run-1")).thenReturn(response);

        assertThat(service.resumeFromStep("run-1")).isSameAs(response);
    }

    /** fe06ebf9: like retry, resume must not wrap the executor (which starts the run thread) in a transaction. */
    @Test
    void resumeFromStep_runsTheExecutorOutsideATransaction() {
        ExecutionCommandService proxied = FakeTransactions.proxy(service, FakeTransactions.manager());
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(workflowExecutor.resumeFromStep("run-1", TriggerSource.RESUME)).thenAnswer(inv -> {
            inTransaction.set(FakeTransactions.active());
            return WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
        });

        proxied.resumeFromStep("run-1");

        assertThat(inTransaction).as("resumeFromStep() ran inside a transaction").isFalse();
    }

    @Test
    void updateNotes_storesNoteInMetadataAndSaves() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").metadata(new HashMap<>(Map.of("other", 1))).build();
        when(executionQueryService.findById("run-1")).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(mock(WorkflowRunResponse.class));

        service.updateNotes("run-1", "my note");

        assertThat(run.getMetadata()).containsEntry("notes", "my note").containsEntry("other", 1);
        verify(runRepository).save(run);
    }

    @Test
    void updateNotes_blankNote_removesEntry() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").metadata(new HashMap<>(Map.of("notes", "old"))).build();
        when(executionQueryService.findById("run-1")).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(mock(WorkflowRunResponse.class));

        service.updateNotes("run-1", "  ");

        assertThat(run.getMetadata()).doesNotContainKey("notes");
        verify(runRepository).save(run);
    }

    @Test
    void updateNotes_nullMetadata_startsFresh() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").metadata(null).build();
        when(executionQueryService.findById("run-1")).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(mock(WorkflowRunResponse.class));

        service.updateNotes("run-1", "n");

        assertThat(run.getMetadata()).containsEntry("notes", "n");
    }
}
