package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.Todo;
import com.automation.entity.Wish;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.DeadLetterRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunDeletionServiceTest {

    private static final String RUN_ID = "run-1";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private DeadLetterRepository deadLetterRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private WishRepository wishRepository;
    @Mock private AuditReportRepository auditReportRepository;
    @Mock private WorkflowExecutionRegistry executionRegistry;
    @Mock private ActivityLogService activityLogService;

    @InjectMocks private RunDeletionService service;

    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        List<StepResult> steps = new ArrayList<>(List.of(new StepResult(), new StepResult()));
        run = WorkflowRun.builder().id(RUN_ID).workflowName("Nightly")
                .workflow(Workflow.builder().id("wf-1").build())
                .status(RunStatus.FAILED).steps(steps).build();
        lenient().when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    void unknownRun_throwsNotFound_andTouchesNothing() {
        when(runRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("nope")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(deadLetterRepository, todoRepository, wishRepository, activityLogService);
        verify(runRepository, never()).delete(any());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PENDING", "RUNNING", "PAUSED"})
    void liveRun_isRefused(RunStatus status) {
        run.setStatus(status);

        assertThatThrownBy(() -> service.delete(RUN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still active");

        verify(runRepository, never()).delete(any());
        verifyNoInteractions(deadLetterRepository, todoRepository);
    }

    @Test
    void runStillRegisteredAsActive_isRefusedEvenWithFinalStatus() {
        when(executionRegistry.isRunActive(RUN_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(RUN_ID)).isInstanceOf(IllegalStateException.class);

        verify(runRepository, never()).delete(any());
    }

    @Test
    void runWithAuditReport_isRefused_toKeepEvidence() {
        when(auditReportRepository.existsByWorkflowRunId(RUN_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(RUN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit report");

        verify(runRepository, never()).delete(any());
        verifyNoInteractions(deadLetterRepository);
    }

    @Test
    void delete_removesDlqEntries_detachesTodosAndWishes_andLogs() {
        Todo incident = Todo.builder().id("t1").workflowRunId(RUN_ID).build();
        Todo fixing = Todo.builder().id("t2").lastFixRunId(RUN_ID)
                .resolutionState(Todo.ResolutionState.RUNNING).build();
        Todo failedFix = Todo.builder().id("t3").lastFixRunId(RUN_ID).workflowRunId("other-run")
                .resolutionState(Todo.ResolutionState.FAILED).build();
        Wish wish = Wish.builder().id("w1").currentRunId(RUN_ID).build();
        when(deadLetterRepository.deleteByWorkflowRunId(RUN_ID)).thenReturn(2);
        when(todoRepository.findByWorkflowRunIdOrLastFixRunId(RUN_ID, RUN_ID))
                .thenReturn(List.of(incident, fixing, failedFix));
        when(wishRepository.findByCurrentRunId(RUN_ID)).thenReturn(List.of(wish));

        service.delete(RUN_ID);

        assertThat(incident.getWorkflowRunId()).isNull();
        assertThat(fixing.getLastFixRunId()).isNull();
        assertThat(fixing.getResolutionState()).isEqualTo(Todo.ResolutionState.IDLE);
        assertThat(failedFix.getLastFixRunId()).isNull();
        assertThat(failedFix.getWorkflowRunId()).isEqualTo("other-run");
        assertThat(failedFix.getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
        assertThat(wish.getCurrentRunId()).isNull();
        verify(todoRepository).saveAll(List.of(incident, fixing, failedFix));
        verify(wishRepository).saveAll(List.of(wish));
        verify(runRepository).delete(run);
        verify(activityLogService).info(eq(LogCategory.EXECUTION),
                contains("Nightly (FAILED, 2 Schritte, 2 DLQ-Eintraege)"),
                eq("RunDeletionService"), eq(RUN_ID), eq("wf-1"));
    }

    @Test
    void delete_withoutWorkflowStepsOrReferences_stillLogsWithNullWorkflowId() {
        run.setWorkflow(null);
        run.setSteps(null);
        when(todoRepository.findByWorkflowRunIdOrLastFixRunId(RUN_ID, RUN_ID)).thenReturn(List.of());
        when(wishRepository.findByCurrentRunId(RUN_ID)).thenReturn(List.of());

        service.delete(RUN_ID);

        verify(runRepository).delete(run);
        verify(activityLogService).info(eq(LogCategory.EXECUTION), contains("0 Schritte, 0 DLQ-Eintraege"),
                anyString(), eq(RUN_ID), isNull());
    }
}
