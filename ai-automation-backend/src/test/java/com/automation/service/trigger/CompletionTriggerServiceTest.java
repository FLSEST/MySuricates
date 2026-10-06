package com.automation.service.trigger;

import com.automation.dto.request.CreateCompletionTriggerRequest;
import com.automation.entity.CompletionTrigger;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.CompletionTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CompletionTriggerServiceTest {

    @Mock private CompletionTriggerRepository repository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private com.automation.service.workflow.WorkflowService workflowService;

    private CompletionTriggerService service;

    private Workflow sourceWf;
    private Workflow targetWf;

    @BeforeEach
    void setUp() {
        service = new CompletionTriggerService(repository, workflowRepository, workflowExecutor, workflowService);

        sourceWf = Workflow.builder().id("wf-src").name("Source").build();
        targetWf = Workflow.builder().id("wf-tgt").name("Target").build();

        // findByIdEager is called in onWorkflowRunCompleted to eagerly load activatedSkills
        lenient().when(workflowService.findByIdEager("wf-tgt")).thenReturn(targetWf);
    }

    @Test
    void findById_existing_returnsTrigger() {
        var trigger = CompletionTrigger.builder().id("t1").sourceWorkflow(sourceWf).targetWorkflow(targetWf).build();
        when(repository.findById("t1")).thenReturn(Optional.of(trigger));

        assertThat(service.findById("t1").getId()).isEqualTo("t1");
    }

    @Test
    void findById_nonExistent_throws() {
        when(repository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validRequest_savesTrigger() {
        when(workflowRepository.findById("wf-src")).thenReturn(Optional.of(sourceWf));
        when(workflowRepository.findById("wf-tgt")).thenReturn(Optional.of(targetWf));
        when(repository.findBySourceWorkflowId("wf-tgt")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateCompletionTriggerRequest(
                "wf-src", "wf-tgt", true, false, false, "desc", Map.of("key", "val"));

        CompletionTrigger result = service.create(request);

        assertThat(result.getSourceWorkflow()).isEqualTo(sourceWf);
        assertThat(result.getTargetWorkflow()).isEqualTo(targetWf);
        assertThat(result.isTriggerOnSuccess()).isTrue();
        assertThat(result.getContextVariables()).containsEntry("key", "val");
    }

    @Test
    void create_selfTrigger_throws() {
        when(workflowRepository.findById("wf-src")).thenReturn(Optional.of(sourceWf));

        var request = new CreateCompletionTriggerRequest(
                "wf-src", "wf-src", true, false, false, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot trigger itself");
    }

    @Test
    void create_circularChain_throws() {
        // Setup: wf-tgt → wf-src already exists, so wf-src → wf-tgt would create a cycle
        when(workflowRepository.findById("wf-src")).thenReturn(Optional.of(sourceWf));
        when(workflowRepository.findById("wf-tgt")).thenReturn(Optional.of(targetWf));

        var existingTrigger = CompletionTrigger.builder()
                .sourceWorkflow(targetWf).targetWorkflow(sourceWf).build();
        when(repository.findBySourceWorkflowId("wf-tgt")).thenReturn(List.of(existingTrigger));

        var request = new CreateCompletionTriggerRequest(
                "wf-src", "wf-tgt", true, false, false, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void enable_revalidatesCycle() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf).enabled(false).build();
        when(repository.findById("t1")).thenReturn(Optional.of(trigger));
        when(repository.findBySourceWorkflowId("wf-tgt")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompletionTrigger result = service.enable("t1");

        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void disable_setsEnabledFalse() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf).enabled(true).build();
        when(repository.findById("t1")).thenReturn(Optional.of(trigger));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompletionTrigger result = service.disable("t1");

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void delete_nonExistent_throws() {
        when(repository.existsById("unknown")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- Event listener / shouldFire tests ---

    @Test
    void onCompleted_successTrigger_firesOnCompleted() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(true).triggerOnFailure(false).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        verify(workflowExecutor).execute(eq(targetWf), any(), eq(TriggerSource.COMPLETION_TRIGGER));
    }

    @Test
    void onCompleted_successTrigger_doesNotFireOnFailed() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(true).triggerOnFailure(false).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.FAILED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.COMPLETION_TRIGGER));
    }

    @Test
    void onCompleted_failureTrigger_firesOnFailed() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(false).triggerOnFailure(true).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.FAILED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        verify(workflowExecutor).execute(eq(targetWf), any(), eq(TriggerSource.COMPLETION_TRIGGER));
    }

    @Test
    void onCompleted_alwaysTrigger_firesOnAnyStatus() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(false).triggerOnFailure(false).triggerAlways(true)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.CANCELLED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        verify(workflowExecutor).execute(eq(targetWf), any(), eq(TriggerSource.COMPLETION_TRIGGER));
    }

    @Test
    void onCompleted_cancelled_doesNotFireNonAlways() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(true).triggerOnFailure(true).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.CANCELLED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.COMPLETION_TRIGGER));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCompleted_contextIncludesSourceInfo() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(true).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null);

        service.onWorkflowRunCompleted(event);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(targetWf), captor.capture(), eq(TriggerSource.COMPLETION_TRIGGER));

        Map<String, Object> context = captor.getValue();
        assertThat(context).containsEntry("completion_trigger_source_run_id", "run-1");
        assertThat(context).containsEntry("completion_trigger_source_workflow_id", "wf-src");
        assertThat(context).containsEntry("completion_trigger_source_status", "COMPLETED");
    }

    @Test
    void onCompleted_executorFails_doesNotThrow() {
        var trigger = CompletionTrigger.builder().id("t1")
                .sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(true).triggerAlways(false)
                .enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));
        when(workflowExecutor.execute(any(), any(), eq(TriggerSource.COMPLETION_TRIGGER))).thenThrow(new RuntimeException("boom"));

        var event = new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null);

        // Should not throw — error is caught and logged
        service.onWorkflowRunCompleted(event);
    }
    /** 986bb1f1: the counters of all fired triggers in one saveAll instead of save per trigger. */
    @Test
    void onCompleted_savesTheCountersOfAllFiredTriggersInOneBatch() {
        Workflow otherTarget = Workflow.builder().id("wf-tgt-2").name("Target 2").build();
        when(workflowService.findByIdEager("wf-tgt-2")).thenReturn(otherTarget);
        var first = CompletionTrigger.builder().id("t1").sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerAlways(true).triggerCount(2).enabled(true).build();
        var notFiring = CompletionTrigger.builder().id("t2").sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(false).triggerOnFailure(true).enabled(true).build();
        var second = CompletionTrigger.builder().id("t3").sourceWorkflow(sourceWf).targetWorkflow(otherTarget)
                .triggerOnSuccess(true).enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(first, notFiring, second));

        service.onWorkflowRunCompleted(new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null));

        verify(repository, times(1)).saveAll(List.of(first, second));
        verify(repository, never()).save(any());
        assertThat(first.getTriggerCount()).isEqualTo(3);
        assertThat(first.getLastTriggeredAt()).isNotNull();
        assertThat(second.getTriggerCount()).isEqualTo(1);
        assertThat(notFiring.getTriggerCount()).isZero();
    }

    /** A trigger whose target does not start does not count — the others are saved anyway. */
    @Test
    void onCompleted_failedStart_isNotCounted_othersAreStillSaved() {
        Workflow brokenTarget = Workflow.builder().id("wf-broken").name("Broken").build();
        when(workflowService.findByIdEager("wf-broken")).thenReturn(brokenTarget);
        when(workflowExecutor.execute(eq(brokenTarget), any(), eq(TriggerSource.COMPLETION_TRIGGER)))
                .thenThrow(new RuntimeException("boom"));
        var broken = CompletionTrigger.builder().id("t1").sourceWorkflow(sourceWf).targetWorkflow(brokenTarget)
                .triggerAlways(true).enabled(true).build();
        var ok = CompletionTrigger.builder().id("t2").sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerAlways(true).enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(broken, ok));

        service.onWorkflowRunCompleted(new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null));

        verify(repository, times(1)).saveAll(List.of(ok));
        assertThat(broken.getTriggerCount()).isZero();
    }

    @Test
    void onCompleted_nothingFired_savesNothing() {
        var trigger = CompletionTrigger.builder().id("t1").sourceWorkflow(sourceWf).targetWorkflow(targetWf)
                .triggerOnSuccess(false).triggerOnFailure(true).enabled(true).build();
        when(repository.findBySourceWorkflowIdAndEnabledTrue("wf-src")).thenReturn(List.of(trigger));

        service.onWorkflowRunCompleted(new WorkflowRunCompletedEvent(this, "run-1", "wf-src", "Source",
                null, null, RunStatus.COMPLETED, Map.of(), Map.of(), null));

        verify(repository, never()).saveAll(any());
        verify(repository, never()).save(any());
    }
}
