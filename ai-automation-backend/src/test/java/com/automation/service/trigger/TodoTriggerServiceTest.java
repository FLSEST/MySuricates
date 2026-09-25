package com.automation.service.trigger;

import com.automation.config.TodoTriggerConfig;
import com.automation.dto.request.CreateTodoTriggerRequest;
import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoTriggerServiceTest {

    @Mock private TodoTriggerRepository repository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;

    private TodoTriggerService service;
    private TodoTriggerConfig config;

    @BeforeEach
    void setUp() {
        config = new TodoTriggerConfig();   // skipUntil = "" → keine Pause
        service = new TodoTriggerService(
                repository, workflowRepository, todoRepository,
                workflowRunRepository, workflowService, workflowExecutor, config);
    }

    // ── CRUD ──────────────────────────────

    @Nested
    class CrudTests {

        @Test
        void create_validRequest_savesAndReturns() {
            Workflow wf = Workflow.builder().id("wf-1").name("Fix").build();
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TodoTrigger result = service.create(new CreateTodoTriggerRequest(
                    "backend,testing", null, null, null, "wf-1", null, "test trigger", 5, true, null));

            assertThat(result.getTagPattern()).isEqualTo("backend,testing");
            assertThat(result.getTargetWorkflow()).isEqualTo(wf);
            assertThat(result.getMaxAttempts()).isEqualTo(5);
            assertThat(result.isAutoClose()).isTrue();
        }

        /** a9bf13d5: the matcher compares against the constant's name, so that is what is stored. */
        @Test
        void create_storesFilterValuesAsTheConstantName() {
            Workflow wf = Workflow.builder().id("wf-1").name("Fix").build();
            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TodoTrigger result = service.create(new CreateTodoTriggerRequest(
                    "bug", null, " workflow ", "high", "wf-1", null, null, null, null, null));
            TodoTrigger unfiltered = service.create(new CreateTodoTriggerRequest(
                    "bug", null, " ", null, "wf-1", null, null, null, null, null));

            assertThat(result.getSourceFilter()).isEqualTo("WORKFLOW");
            assertThat(result.getMinPriority()).isEqualTo("HIGH");
            assertThat(unfiltered.getSourceFilter()).isNull();
            assertThat(unfiltered.getMinPriority()).isNull();
        }

        /** Before, "robot" was stored and the trigger silently never fired. */
        @Test
        void create_unknownFilterValue_isRejectedNamingTheAllowedValues() {
            assertThatThrownBy(() -> service.create(new CreateTodoTriggerRequest(
                    "bug", null, "robot", null, "wf-1", null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sourceFilter muss USER, AI oder WORKFLOW sein, nicht 'robot'");
            assertThatThrownBy(() -> service.create(new CreateTodoTriggerRequest(
                    "bug", null, null, "urgent", "wf-1", null, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("minPriority muss LOW, MEDIUM, HIGH oder CRITICAL sein, nicht 'urgent'");
            verify(repository, never()).save(any());
        }

        @Test
        void create_unknownWorkflow_throws() {
            when(workflowRepository.findById("wf-x")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(new CreateTodoTriggerRequest(
                    "tag", null, null, null, "wf-x", null, null, null, null, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void enable_setsEnabledTrue() {
            TodoTrigger trigger = TodoTrigger.builder().id("t-1").enabled(false).build();
            when(repository.findById("t-1")).thenReturn(Optional.of(trigger));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TodoTrigger result = service.enable("t-1");

            assertThat(result.isEnabled()).isTrue();
        }

        @Test
        void disable_setsEnabledFalse() {
            TodoTrigger trigger = TodoTrigger.builder().id("t-1").enabled(true).build();
            when(repository.findById("t-1")).thenReturn(Optional.of(trigger));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            TodoTrigger result = service.disable("t-1");

            assertThat(result.isEnabled()).isFalse();
        }

        @Test
        void delete_nonExistent_throws() {
            when(repository.existsById("t-x")).thenReturn(false);

            assertThatThrownBy(() -> service.delete("t-x"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Tag Matching ──────────────────────

    @Nested
    class MatchingTests {

        @Test
        void matches_allTagsPresent_returnsTrue() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend,testing").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend", "testing", "auto:workflow")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isTrue();
        }

        @Test
        void matches_missingTag_returnsFalse() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend,security").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend", "testing")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isFalse();
        }

        @Test
        void matches_emptyTagPattern_matchesAll() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("anything")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isTrue();
        }

        @Test
        void matches_projectFilter_wrongProject_returnsFalse() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").projectId("proj-1").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .projectId("proj-2")
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isFalse();
        }

        @Test
        void matches_projectFilter_sameProject_returnsTrue() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").projectId("proj-1").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .projectId("proj-1")
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isTrue();
        }

        @Test
        void matches_sourceFilter_wrongSource_returnsFalse() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").sourceFilter("WORKFLOW").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.USER).build();

            assertThat(service.matches(trigger, todo)).isFalse();
        }

        @Test
        void matches_priorityFilter_belowMinimum_returnsFalse() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").minPriority("HIGH").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.MEDIUM).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isFalse();
        }

        @Test
        void matches_priorityFilter_atMinimum_returnsTrue() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").minPriority("HIGH").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isTrue();
        }

        @Test
        void matches_priorityFilter_aboveMinimum_returnsTrue() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .tagPattern("backend").minPriority("HIGH").build();
            Todo todo = Todo.builder()
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.CRITICAL).source(Todo.Source.WORKFLOW).build();

            assertThat(service.matches(trigger, todo)).isTrue();
        }
    }

    // ── Event Handling ──────────────────────

    @Nested
    class BacklogProcessingTests {

        @Test
        void processBacklog_usesTriggerScopedQuery_andFires() {
            Workflow targetWf = Workflow.builder().id("wf-fix").name("Hunt Quality Rebuild").build();
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("hunt-rebuild")
                    .projectId("proj-1").sourceFilter("WORKFLOW")
                    .targetWorkflow(targetWf).maxAttempts(2)
                    .enabled(true).processBacklog(true).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-hd").title("Hunt rebuild: heidelberg").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("hunt-rebuild", "auto:workflow")))
                    .projectId("proj-1")
                    .priority(Todo.Priority.MEDIUM).source(Todo.Source.WORKFLOW)
                    .fixAttempts(1).build();
            // Scoped query returns the trigger-relevant todo — regardless of how
            // many unrelated high-priority todos exist globally (starvation fix).
            when(todoRepository.findBacklogCandidatesForTrigger(
                    eq("proj-1"), eq(Todo.Source.WORKFLOW), eq(2), any()))
                    .thenReturn(List.of(todo));

            when(workflowService.findByIdEager("wf-fix")).thenReturn(targetWf);
            WorkflowRun run = WorkflowRun.builder().id("run-2").build();
            when(workflowExecutor.execute(eq(targetWf), anyMap(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER))).thenReturn(run);
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.processBacklog();

            verify(workflowExecutor).execute(eq(targetWf), anyMap(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER));
            assertThat(todo.getFixAttempts()).isEqualTo(2);
            // The old unscoped global query must not be used anymore
            verify(todoRepository, never()).findBacklogCandidates(anyInt(), any());
        }

        @Test
        void processBacklog_noBacklogTriggers_doesNothing() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("hunt-rebuild")
                    .targetWorkflow(Workflow.builder().id("wf-1").build())
                    .maxAttempts(2).enabled(true).processBacklog(false).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            service.processBacklog();

            verifyNoInteractions(todoRepository);
            verifyNoInteractions(workflowExecutor);
        }
    }

    // ── Pause-Fenster (automation.todo-trigger.skip-until) ──

    @Nested
    class PauseWindowTests {

        private static final String FUTURE = "2999-01-01T00:00:00Z";
        private static final String PAST = "2000-01-01T00:00:00Z";

        @Test
        void processBacklog_whilePaused_touchesNothing() {
            config.setSkipUntil(FUTURE);

            service.processBacklog();

            // Nicht einmal die Trigger-Liste wird geladen — die Pause greift vor jeder DB-Arbeit.
            verifyNoInteractions(repository);
            verifyNoInteractions(todoRepository);
            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void onTodoCreated_whilePaused_touchesNothing() {
            config.setSkipUntil(FUTURE);
            Todo todo = Todo.builder()
                    .id("todo-1").title("Fix bug").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.AI).build();

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verifyNoInteractions(repository);
            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void processBacklog_afterWindowExpired_runsNormally() {
            config.setSkipUntil(PAST);
            when(repository.findByEnabledTrue()).thenReturn(List.of());

            service.processBacklog();

            // Kein Aufraeumen noetig: ein abgelaufenes Fenster gibt den Loop von selbst frei.
            verify(repository).findByEnabledTrue();
        }

        @Test
        void unparseableSkipUntil_failsOpen_soTheLoopKeepsRunning() {
            config.setSkipUntil("naechste Woche");
            when(repository.findByEnabledTrue()).thenReturn(List.of());

            service.processBacklog();

            verify(repository).findByEnabledTrue();
        }

        @Test
        void startupLog_withUnparseableValue_doesNotThrow() {
            config.setSkipUntil("kaputt");

            service.logPauseWindowAtStartup();   // darf den Boot nicht verhindern
        }
    }

    @Nested
    class EventHandlingTests {

        @Test
        void onTodoCreated_matchingTrigger_firesWorkflow() {
            Workflow targetWf = Workflow.builder().id("wf-fix").name("Auto-Fix").build();
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("backend,testing")
                    .targetWorkflow(targetWf).maxAttempts(3).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-1").title("Write tests").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("backend", "testing", "auto:workflow")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW)
                    .fixAttempts(0).build();

            when(workflowService.findByIdEager("wf-fix")).thenReturn(targetWf);
            WorkflowRun run = WorkflowRun.builder().id("run-1").build();
            when(workflowExecutor.execute(eq(targetWf), anyMap(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER))).thenReturn(run);
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verify(workflowExecutor).execute(eq(targetWf), anyMap(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER));
            assertThat(todo.getFixAttempts()).isEqualTo(1);
            assertThat(todo.getLastFixRunId()).isEqualTo("run-1");
        }

        @Test
        void onTodoCreated_noMatchingTrigger_doesNothing() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("security")
                    .targetWorkflow(Workflow.builder().id("wf-1").build())
                    .maxAttempts(3).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-1").title("Write tests").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("backend", "testing")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW)
                    .fixAttempts(0).build();

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void onTodoCreated_maxAttemptsReached_skips() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("backend")
                    .targetWorkflow(Workflow.builder().id("wf-1").build())
                    .maxAttempts(3).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-1").title("Test").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW)
                    .fixAttempts(3).build();

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void onTodoCreated_fixRunStillRunning_skips() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("backend")
                    .targetWorkflow(Workflow.builder().id("wf-1").build())
                    .maxAttempts(3).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-1").title("Test").status(Todo.Status.OPEN)
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW)
                    .fixAttempts(1).lastFixRunId("run-old").build();

            WorkflowRun runningRun = WorkflowRun.builder().id("run-old").status(RunStatus.RUNNING).build();
            when(workflowRunRepository.findById("run-old")).thenReturn(Optional.of(runningRun));

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void onTodoCreated_todoNotOpen_doesNothing() {
            Todo todo = Todo.builder()
                    .id("todo-1").title("Done").status(Todo.Status.DONE)
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW).build();

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            verifyNoInteractions(repository);
            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void onTodoCreated_contextContainsTodoData() {
            Workflow targetWf = Workflow.builder().id("wf-fix").name("Fix").build();
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("trig-1").tagPattern("backend")
                    .targetWorkflow(targetWf).maxAttempts(3)
                    .contextTemplate(Map.of("extra_key", "extra_value")).build();
            when(repository.findByEnabledTrue()).thenReturn(List.of(trigger));

            Todo todo = Todo.builder()
                    .id("todo-1").title("Fix bug").description("Details here")
                    .status(Todo.Status.OPEN).projectId("proj-1")
                    .tags(TodoMapper.listToJson(List.of("backend")))
                    .priority(Todo.Priority.HIGH).source(Todo.Source.WORKFLOW)
                    .referenceType("workflow_run").referenceId("wf-source")
                    .fixAttempts(0).build();

            when(workflowService.findByIdEager("wf-fix")).thenReturn(targetWf);
            WorkflowRun run = WorkflowRun.builder().id("run-1").build();
            when(workflowExecutor.execute(eq(targetWf), anyMap(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER))).thenReturn(run);
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.onTodoCreated(new TodoCreatedEvent(this, todo));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
            verify(workflowExecutor).execute(eq(targetWf), contextCaptor.capture(), isNull(), anyMap(), eq(TriggerSource.TODO_TRIGGER));

            Map<String, Object> context = contextCaptor.getValue();
            assertThat(context).containsEntry("todo_id", "todo-1");
            assertThat(context).containsEntry("todo_title", "Fix bug");
            assertThat(context).containsEntry("todo_description", "Details here");
            assertThat(context).containsEntry("todo_priority", "HIGH");
            assertThat(context).containsEntry("todo_project_id", "proj-1");
            assertThat(context).containsEntry("extra_key", "extra_value");
            assertThat(context).containsEntry("todo_trigger_id", "trig-1");
        }
    }
}
