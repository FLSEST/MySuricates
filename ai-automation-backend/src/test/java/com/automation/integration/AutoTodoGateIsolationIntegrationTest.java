package com.automation.integration;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.*;
import com.automation.service.todo.*;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real SQLite lookups and both writers without starting workflows or calling AI. */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AutoTodoGateIsolationIntegrationTest {
    @Autowired private TodoRepository todos;
    @Autowired private ProjectRepository projects;
    @Autowired private PlatformTransactionManager transactions;

    private String projectId;
    private GateStatusResolver resolver;
    private WorkflowRunRepository runs;
    private AutoTodoService autoTodos;
    private PendingTodoApprovalService approvals;

    @BeforeEach
    void setUp() {
        projectId = projects.save(Project.builder().name("Gate isolation")
                .type(ProjectType.CODE).status(ProjectStatus.STOPPED).path("/tmp/gate-isolation").build()).getId();
        resolver = mock(GateStatusResolver.class);
        runs = mock(WorkflowRunRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var text = new AutoTodoTextBuilder(mock(StepResultRepository.class));
        var granular = new GranularTodoService(todos, mock(TodoSimilarityService.class), transactions, publisher, text);
        approvals = new PendingTodoApprovalService(todos, runs, transactions, publisher, text);
        autoTodos = new AutoTodoService(todos, resolver, mock(ProjectPathResolver.class),
                mock(AppSettingsRepository.class), transactions, publisher, text, approvals, granular,
                mock(com.automation.service.activitylog.ActivityLogService.class));
    }

    @ParameterizedTest
    @CsvSource({"YELLOW,auto", "RED,auto", "YELLOW,track", "RED,track", "GREEN,track"})
    void gateUpsert_preservesGranularFindingAcrossRepeatedRuns(GateStatus status, String mode) {
        when(resolver.resolve(any())).thenReturn(new GateResult(status, "smoke", "Gate summary"));

        autoTodos.onWorkflowRunCompleted(event("run-1", mode));

        assertThat(workflowTodos()).hasSize(2);
        Todo item = granularTodo();
        Todo gate = gateTodo();
        String itemId = item.getId();
        String gateId = gate.getId();
        assertItemPreserved(item);
        assertThat(gate.getDescription()).contains("Gate summary");

        autoTodos.onWorkflowRunCompleted(event("run-2", mode));

        assertThat(workflowTodos()).hasSize(2);
        assertThat(granularTodo().getId()).isEqualTo(itemId);
        assertThat(gateTodo().getId()).isEqualTo(gateId);
        assertThat(gateTodo().getWorkflowRunId()).isEqualTo("run-2");
        assertItemPreserved(granularTodo());
    }

    @Test
    void approval_createsThenUpdatesGateWithoutOverwritingAnExistingItem() {
        Todo item = todos.save(Todo.builder().title("Smoke granular item").description("Granular diagnosis")
                .priority(Todo.Priority.HIGH).status(Todo.Status.OPEN).source(Todo.Source.WORKFLOW)
                .projectId(projectId).referenceType("workflow_run").referenceId("wf-smoke")
                .tags(TodoMapper.listToJson(List.of("auto:workflow", "item:123", "category:smoke", "custom"))).build());
        Workflow workflow = Workflow.builder().id("wf-smoke").build();
        WorkflowRun run = WorkflowRun.builder().id("approval-run").workflow(workflow).metadata(new HashMap<>()).build();
        Map<String, Object> pending = Map.of("title", "Approved gate", "priority", "MEDIUM",
                "description", "Gate summary", "projectId", projectId, "category", "smoke",
                "tags", TodoMapper.listToJson(List.of("auto:workflow", "gate:yellow", "category:smoke")));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        for (int i = 0; i < 2; i++) {
            run.getMetadata().put("pending_todos", List.of(pending));
            approvals.approvePendingTodos(run.getId(), true);
            assertThat(workflowTodos()).hasSize(2);
            assertItemPreserved(todos.findById(item.getId()).orElseThrow());
            assertThat(gateTodo().getDescription()).isEqualTo("Gate summary");
            assertThat(run.getMetadata()).doesNotContainKey("pending_todos");
        }
    }

    private WorkflowRunCompletedEvent event(String runId, String mode) {
        return new WorkflowRunCompletedEvent(this, runId, "wf-smoke", "Smoke audit", projectId, "/tmp/gate-isolation",
                RunStatus.COMPLETED, Map.of("todo_items", List.of(Map.of("title", "Smoke granular item",
                        "description", "Granular diagnosis", "priority", "HIGH", "tags", List.of("custom")))),
                Map.of("autoTodo", Map.of("enabled", true, "mode", mode)), Map.of());
    }

    @Test
    void gateLookup_ignoresItemsEvenWithGateTagsAndRequiresAnExactCategory() {
        seedTodo("Mixed item", "smoke", List.of("gate:red", "item:123"));
        seedTodo("Longer category", "smoke-extra", List.of("gate:red"));
        seedTodo("No gate", "smoke", List.of("note:gate:red"));
        Todo done = seedTodo("Done gate", "smoke", List.of("gate:yellow"));
        done.setStatus(Todo.Status.DONE);
        todos.save(done);
        Todo expected = seedTodo("Current gate", "smoke", List.of("gate:yellow"));
        expected.setStatus(Todo.Status.IN_PROGRESS);
        todos.saveAndFlush(expected);

        assertThat(todos.findOpenGateByCompositeKey(projectId, "workflow_run", "wf-smoke", "category:smoke"))
                .get().extracting(Todo::getId).isEqualTo(expected.getId());
        assertThat(todos.findOpenByCompositeKey(projectId, "workflow_run", "wf-smoke", "item:123"))
                .get().extracting(Todo::getTitle).isEqualTo("Mixed item");
    }

    @Test
    void gateLookup_treatsPercentAndUnderscoreInCategoriesLiterally() {
        seedTodo("Similar category", "smokeXother", List.of("gate:red"));
        Todo expected = seedTodo("Literal category", "smoke_%", List.of("gate:red"));

        assertThat(todos.findOpenGateByCompositeKey(projectId, "workflow_run", "wf-smoke", "category:smoke_%"))
                .get().extracting(Todo::getId).isEqualTo(expected.getId());
    }

    private Todo seedTodo(String title, String category, List<String> extraTags) {
        var tags = new java.util.ArrayList<>(extraTags);
        tags.add("auto:workflow");
        tags.add("category:" + category);
        return todos.save(Todo.builder().title(title).status(Todo.Status.OPEN).source(Todo.Source.WORKFLOW)
                .projectId(projectId).referenceType("workflow_run").referenceId("wf-smoke")
                .tags(TodoMapper.listToJson(tags)).build());
    }

    private List<Todo> workflowTodos() {
        todos.flush();
        return todos.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                projectId, Todo.Source.WORKFLOW, "workflow_run", "wf-smoke", Todo.Status.DONE);
    }

    private Todo granularTodo() {
        return workflowTodos().stream().filter(t -> t.getTags().contains("\"item:")).findFirst().orElseThrow();
    }

    private Todo gateTodo() {
        return workflowTodos().stream().filter(t -> t.getTags().contains("\"gate:")).findFirst().orElseThrow();
    }

    private void assertItemPreserved(Todo item) {
        assertThat(item.getTitle()).isEqualTo("Smoke granular item");
        assertThat(item.getDescription()).contains("Granular diagnosis").doesNotContain("Gate summary");
        assertThat(item.getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(item.getTags()).contains("\"item:", "\"category:smoke\"", "\"custom\"").doesNotContain("\"gate:");
    }
}
