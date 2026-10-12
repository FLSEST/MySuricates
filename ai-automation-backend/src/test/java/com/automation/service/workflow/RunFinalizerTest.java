package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunFinalizerTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Mock private com.automation.security.RunTokenService runTokenService;
    @InjectMocks private RunFinalizer finalizer;

    private WorkflowRun run;
    private Project project;

    @BeforeEach
    void setUp() {
        run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED)
                .startedAt(Instant.now().minusSeconds(5))
                .metadata(new HashMap<>(Map.of("existing", "kept"))).build();
        project = Project.builder().id("p-1").name("P").type(ProjectType.CODE).path("/workspace/test").build();
    }

    private WorkflowRunCompletedEvent capturedEvent() {
        ArgumentCaptor<WorkflowRunCompletedEvent> captor = ArgumentCaptor.forClass(WorkflowRunCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    void finish_mergesRunMetadataAndSaves() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.setRunMetadata("gate", "GREEN");

        finalizer.finish(run, context, "wf-1", "Workflow", project, Map.of("autoTodo", Map.of("enabled", true)));

        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getDurationMs()).isGreaterThanOrEqualTo(5000L);
        assertThat(run.getMetadata()).containsEntry("existing", "kept").containsEntry("gate", "GREEN");
        verify(runRepository).save(run);
    }

    @Test
    void finish_emptyRunMetadata_leavesRunMetadataUntouched() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run-1").build();

        finalizer.finish(run, context, "wf-1", "Workflow", project, Map.of());

        assertThat(run.getMetadata()).containsExactly(Map.entry("existing", "kept"));
    }

    @Test
    void finish_nullContext_leavesRunMetadataUntouched() {
        finalizer.finish(run, null, "wf-1", "Workflow", project, Map.of());

        assertThat(run.getMetadata()).containsExactly(Map.entry("existing", "kept"));
        verify(runRepository).save(run);
    }

    @Test
    void finish_publishesCompletedEventAfterBroadcast() {
        finalizer.finish(run, null, "wf-1", "Workflow", project, Map.of("autoTodo", Map.of("enabled", true)));

        var order = inOrder(runRepository, broadcaster, eventPublisher);
        order.verify(runRepository).save(run);
        order.verify(broadcaster).broadcastRunStatus("run-1", RunStatus.COMPLETED);
        order.verify(eventPublisher).publishEvent(any(WorkflowRunCompletedEvent.class));

        WorkflowRunCompletedEvent event = capturedEvent();
        assertThat(event.getRunId()).isEqualTo("run-1");
        assertThat(event.getWorkflowId()).isEqualTo("wf-1");
        assertThat(event.getWorkflowName()).isEqualTo("Workflow");
        assertThat(event.getProjectId()).isEqualTo("p-1");
        assertThat(event.getProjectPath()).isEqualTo("/workspace/test");
        assertThat(event.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(event.getWorkflowMetadata()).isEqualTo(Map.of("autoTodo", Map.of("enabled", true)));
        assertThat(event.getRunMetadata()).containsEntry("existing", "kept");
    }

    @Test
    void finish_eventPublishFails_isLoggedNotThrown() {
        doThrow(new RuntimeException("listener exploded")).when(eventPublisher).publishEvent(any(WorkflowRunCompletedEvent.class));

        assertThatCode(() -> finalizer.finish(run, null, "wf-1", "Workflow", project, Map.of()))
                .doesNotThrowAnyException();

        verify(runRepository).save(run);
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.COMPLETED);
    }

    @Test
    void finish_nullProject_eventHasNullProjectFields() {
        finalizer.finish(run, null, "wf-1", "Workflow", null, Map.of());

        WorkflowRunCompletedEvent event = capturedEvent();
        assertThat(event.getProjectId()).isNull();
        assertThat(event.getProjectPath()).isNull();
    }

    @Test
    void finish_failedRun_broadcastsFailedStatusAndError() {
        run.setStatus(RunStatus.FAILED);
        run.setError(new HashMap<>(Map.of("message", "boom")));

        finalizer.finish(run, null, "wf-1", "Workflow", project, Map.of());

        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.FAILED);
        assertThat(capturedEvent().getError()).containsEntry("message", "boom");
    }
}
