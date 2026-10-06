package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.executor.ExecutionContext;
import com.automation.service.todo.TodoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoUpdateBlockExecutorTest {

    @Mock private TodoService todoService;

    /** 01:30 in Berlin is 23:30 UTC the day before – the note should carry the day of the instance (d6c0afe6). */
    private static final Clock BERLIN_NIGHT =
            Clock.fixed(Instant.parse("2026-09-30T23:30:00Z"), ZoneId.of("Europe/Berlin"));

    private TodoUpdateBlockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TodoUpdateBlockExecutor(todoService, BERLIN_NIGHT);
    }

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
    }

    private BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("todo-update").config(config).build();
    }

    @Test
    void supports_todoUpdateOnly() {
        assertThat(executor.supports("todo-update")).isTrue();
        assertThat(executor.supports("todo-create")).isFalse();
    }

    @Test
    void execute_updatesOnlyProvidedFields() {
        when(todoService.update(eq("todo-5"), any()))
                .thenReturn(Todo.builder().id("todo-5").title("Neu").build());

        Map<String, Object> config = new HashMap<>();
        config.put("id", "todo-5");
        config.put("title", "Neu");
        config.put("priority", "LOW");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).update(eq("todo-5"), argThat(req ->
                "Neu".equals(req.title())
                        && req.priority() == Todo.Priority.LOW
                        && req.description() == null
                        && req.tags() == null
                        && req.status() == null));
    }

    @Test
    void execute_idFromTemplateVariable_isResolved() {
        when(todoService.update(eq("todo-77"), any()))
                .thenReturn(Todo.builder().id("todo-77").title("T").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("todo_id", "todo-77");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "{{ todo_id }}");
        config.put("tags", List.of("triaged"));

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).update(eq("todo-77"), argThat(req -> req.tags().equals(List.of("triaged"))));
    }

    @Test
    void execute_priorityPlaceholder_isResolved() {
        when(todoService.update(eq("todo-77"), any()))
                .thenReturn(Todo.builder().id("todo-77").title("T").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("todo_id", "todo-77");
        ctx.setVariable("new_priority", "HIGH");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "{{ todo_id }}");
        config.put("priority", "{{ new_priority }}");

        executor.execute(block(config), ctx);

        verify(todoService).update(eq("todo-77"), argThat(req -> req.priority() == Todo.Priority.HIGH));
    }

    @Test
    void execute_missingId_failsWithoutServiceCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("title", "x");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("id");
        verify(todoService, never()).update(anyString(), any());
    }

    @Test
    void execute_updates_appliesTheListAndReportsCounts() {
        String id = "11111111-1111-1111-1111-111111111111";
        when(todoService.findById(id)).thenReturn(Todo.builder().id(id).projectId("proj-1").status(Todo.Status.OPEN).build());
        when(todoService.update(eq(id), any())).thenReturn(Todo.builder().id(id).build());
        ExecutionContext ctx = buildContext();
        ctx.setVariable("project_id", "proj-1");
        ctx.setVariable("workflow_name", "Batch");
        ctx.setVariable("list", "[{\"id\":\"" + id + "\",\"status\":\"IN_PROGRESS\"}]");
        Map<String, Object> config = new HashMap<>();
        config.put("updates", "{{ list }}");

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).startsWith("✓ 1 aktualisiert, 0 übersprungen");
    }

    @Test
    void execute_updates_noteCarriesTheDateOfTheInstanceZoneNotUtc() {
        String id = "22222222-2222-2222-2222-222222222222";
        when(todoService.findById(id)).thenReturn(Todo.builder().id(id).projectId("proj-1").status(Todo.Status.OPEN)
                .description("Alt").build());
        when(todoService.update(eq(id), any())).thenReturn(Todo.builder().id(id).build());
        ExecutionContext ctx = buildContext();
        ctx.setVariable("project_id", "proj-1");
        ctx.setVariable("workflow_name", "Batch");
        Map<String, Object> config = new HashMap<>();
        config.put("updates", "[{\"id\":\"" + id + "\",\"note\":\"gestartet\"}]");

        executor.execute(block(config), ctx);

        verify(todoService).update(eq(id), argThat(request ->
                request.description().contains("— 2026-10-01 · Batch (Lauf run-1): gestartet")));
    }

    /** An empty {@code id} (editor default cleared) counts as not set – as in the analyzer (requiredOneOf). */
    @Test
    void execute_blankIdNextToUpdates_runsTheUpdates() {
        ExecutionContext ctx = buildContext();
        ctx.setVariable("project_id", "proj-1");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "");
        config.put("updates", "[]");

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).startsWith("✓ 0 aktualisiert");
    }

    @Test
    void execute_idAndUpdates_fail() {
        Map<String, Object> config = new HashMap<>();
        config.put("id", "x");
        config.put("updates", "[]");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("entweder 'id' oder 'updates'");
    }

    @Test
    void execute_updatesWithoutProject_fail() {
        Map<String, Object> config = new HashMap<>();
        config.put("updates", "[]");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("braucht ein Projekt");
    }

    @Test
    void execute_emptyTitleAndDescription_areNotSet_4a2edda6() {
        when(todoService.update(eq("todo-5"), any())).thenReturn(Todo.builder().id("todo-5").title("Alt").build());
        Map<String, Object> config = new HashMap<>();
        config.put("id", "todo-5");
        config.put("title", "");
        config.put("description", "  ");
        config.put("priority", "LOW");

        executor.execute(block(config), buildContext());

        verify(todoService).update(eq("todo-5"), argThat(req -> req.title() == null && req.description() == null
                && req.priority() == Todo.Priority.LOW));
    }
}
