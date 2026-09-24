package com.automation.service.goal;

import com.automation.dto.projection.GoalRelationRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.dto.projection.TodoTriggerMatchRow;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.dto.response.GoalRelationsResponse.OriginKind;
import com.automation.dto.response.GoalRelationsResponse.TodoRelation;
import com.automation.dto.response.GoalRelationsResponse.WorkerKind;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relationskarte eines Ziels (TODO c6ea8f5c): Herkunftsarten, Bearbeitung per Fix-Workflow und
 * TodoTrigger, Ergebnis des letzten Fix-Laufs, Kandidaten – und alles gebuendelt, eine Abfrage je
 * Art statt eine je TODO.
 */
@ExtendWith(MockitoExtension.class)
class GoalRelationsServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-24T08:00:00Z");

    @Mock private GoalRepository goalRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private TodoTriggerRepository triggerRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;

    private GoalRelationsService service;

    @BeforeEach
    void setUp() {
        service = new GoalRelationsService(goalRepository, todoRepository, projectRepository, workflowRepository,
                runRepository, triggerRepository, findingTodoRepository);
    }

    @Test
    void unknownGoal_isNotFound() {
        when(goalRepository.existsById("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.relations("nope", List.of())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void originKinds_auditBeforeRun_healthByReference_runByRunOrReference_agentAndUserBySource() {
        goal(List.of(
                row("audit").source(Todo.Source.WORKFLOW).run("run-audit").ref("audit_finding", "f-1").build(),
                row("health").source(Todo.Source.WORKFLOW).ref("health_check", "disk").build(),
                row("gate").source(Todo.Source.WORKFLOW).run("run-gate").ref("workflow_run", "wf-gate").build(),
                row("gone").source(Todo.Source.WORKFLOW).run("run-deleted").ref("workflow_run", "wf-gate").build(),
                row("agent").source(Todo.Source.AI).agent("claude-code").build(),
                row("user").build()));
        when(findingTodoRepository.findByTodoIdIn(anyCollection())).thenReturn(List.of(
                AuditFindingTodo.builder().findingId("f-2").todoId("audit").build()));
        when(runRepository.findRunStates(anyCollection())).thenReturn(List.of(
                new RunStateRow("run-audit", "wf-audit", "Audit (alt)", RunStatus.COMPLETED, T0, T0.plusSeconds(60)),
                new RunStateRow("run-gate", "wf-gate", "Gate", RunStatus.FAILED, T0, T0.plusSeconds(5))));
        when(workflowRepository.findIdAndNameByIdIn(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"wf-audit", "Security Audit"}, new Object[]{"wf-gate", "Quality Gate"}));

        GoalRelationsResponse response = service.relations("g", List.of());

        TodoRelation audit = byId(response, "audit");
        assertThat(audit.origin().kind()).isEqualTo(OriginKind.AUDIT);
        assertThat(audit.origin().findingIds()).containsExactlyInAnyOrder("f-1", "f-2");
        assertThat(audit.origin().workflowName()).as("aktueller Name vor dem Namen beim Lauf").isEqualTo("Security Audit");
        assertThat(byId(response, "health").origin().kind()).isEqualTo(OriginKind.HEALTH);

        TodoRelation gate = byId(response, "gate");
        assertThat(gate.origin().kind()).isEqualTo(OriginKind.WORKFLOW_RUN);
        assertThat(gate.origin().workflowId()).isEqualTo("wf-gate");
        assertThat(gate.origin().runStatus()).isEqualTo(RunStatus.FAILED);

        TodoRelation gone = byId(response, "gone");
        assertThat(gone.origin().workflowId()).as("Lauf geloescht: Workflow aus der Referenz").isEqualTo("wf-gate");
        assertThat(gone.origin().workflowName()).isEqualTo("Quality Gate");
        assertThat(gone.origin().runStatus()).isNull();

        assertThat(byId(response, "agent").origin().kind()).isEqualTo(OriginKind.AGENT);
        assertThat(byId(response, "agent").origin().agent()).isEqualTo("claude-code");
        assertThat(byId(response, "user").origin().kind()).isEqualTo(OriginKind.USER);
    }

    @Test
    void workers_linkedWorkflowAndEveryMatchingActiveTrigger_deletedTargetsDropped() {
        goal(List.of(
                row("release").tags("release", "docs").linked("wf-fix").build(),
                row("backend").tags("backend").priority(Todo.Priority.LOW).build(),
                row("nothing").build()));
        when(triggerRepository.findActiveMatchRows()).thenReturn(List.of(
                new TodoTriggerMatchRow("tr-release", "release", null, null, null, "wf-release", "Release-Fixer"),
                new TodoTriggerMatchRow("tr-backend-high", "backend", null, null, "HIGH", "wf-backend", null),
                new TodoTriggerMatchRow("tr-deleted", "docs", null, null, null, "wf-deleted", "Weg")));
        when(workflowRepository.findIdAndNameByIdIn(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{"wf-fix", "Fix"}, new Object[]{"wf-release", "Release"}, new Object[]{"wf-backend", "Backend"}));

        GoalRelationsResponse response = service.relations("g", List.of());

        assertThat(response.activeTriggers()).isEqualTo(3);
        assertThat(byId(response, "release").workers())
                .extracting(w -> w.kind() + ":" + w.workflowName() + ":" + w.triggerLabel())
                .containsExactly("LINKED:Fix:null", "TRIGGER:Release:Release-Fixer");
        assertThat(byId(response, "backend").workers()).as("unter der Mindest-Prioritaet").isEmpty();
        assertThat(byId(response, "nothing").workers()).isEmpty();
        assertThat(byId(response, "release").workers().get(1).triggerId()).isEqualTo("tr-release");
        assertThat(byId(response, "release").workers()).noneMatch(w -> w.kind() == WorkerKind.TRIGGER && "wf-deleted".equals(w.workflowId()));
    }

    @Test
    void result_lastFixRunStatusAndTime_absentWithoutAnyFix() {
        goal(List.of(
                row("fixed").fix("run-fix", 2, Todo.ResolutionState.SUCCEEDED).build(),
                row("running").fix("run-live", 1, Todo.ResolutionState.RUNNING).build(),
                row("never").build()));
        when(runRepository.findRunStates(anyCollection())).thenReturn(List.of(
                new RunStateRow("run-fix", "wf", "Fix", RunStatus.COMPLETED, T0, T0.plusSeconds(90)),
                new RunStateRow("run-live", "wf", "Fix", RunStatus.RUNNING, T0, null)));

        GoalRelationsResponse response = service.relations("g", List.of());

        var fixed = byId(response, "fixed").result();
        assertThat(fixed.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fixed.at()).isEqualTo(T0.plusSeconds(90));
        assertThat(fixed.fixAttempts()).isEqualTo(2);
        assertThat(fixed.resolutionState()).isEqualTo(Todo.ResolutionState.SUCCEEDED);
        assertThat(byId(response, "running").result().at()).as("laeuft noch: Startzeit").isEqualTo(T0);
        assertThat(byId(response, "never").result()).isNull();
    }

    @Test
    void members_inWorkOrder_thenCandidatesInRequestOrder_cappedAndMarked() {
        goal(List.of(
                row("low").priority(Todo.Priority.LOW).build(),
                row("ranked").priority(Todo.Priority.LOW).rank(1).build(),
                row("high").priority(Todo.Priority.HIGH).build()));
        List<String> include = new ArrayList<>(List.of("c2", "c1", "high", " "));
        IntStream.range(0, 30).forEach(i -> include.add("extra" + i));
        when(todoRepository.findUnassignedRelationRows(anyCollection())).thenReturn(List.of(
                row("c1").build(), row("c2").build()));

        GoalRelationsResponse response = service.relations("g", include);

        assertThat(response.todos()).extracting(TodoRelation::id).containsExactly("ranked", "high", "low", "c2", "c1");
        assertThat(response.todos()).extracting(TodoRelation::candidate).containsExactly(false, false, false, true, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(todoRepository).findUnassignedRelationRows(asked.capture());
        assertThat(asked.getValue()).hasSize(GoalRelationsService.MAX_CANDIDATES)
                .as("eigene TODOs und Leerwerte werden nicht als Kandidaten angefragt").doesNotContain("high", " ");
    }

    @Test
    void everythingIsLoadedInBatches_oneQueryPerKind_noneWithoutIds() {
        List<GoalRelationRow> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(row("t" + i).project("p" + (i % 3)).source(Todo.Source.WORKFLOW).run("run" + i)
                    .linked("wf" + i).fix("fix" + i, 1, Todo.ResolutionState.FAILED).build());
        }
        goal(rows);

        service.relations("g", List.of());

        verify(todoRepository, times(1)).findGoalRelationRows("g");
        verify(projectRepository, times(1)).findIdAndNameByIdIn(anyCollection());
        verify(workflowRepository, times(1)).findIdAndNameByIdIn(anyCollection());
        verify(runRepository, times(1)).findRunStates(anyCollection());
        verify(triggerRepository, times(1)).findActiveMatchRows();
        verify(findingTodoRepository, times(1)).findByTodoIdIn(anyCollection());
        verify(todoRepository, never()).findUnassignedRelationRows(any());
    }

    @Test
    void emptyGoal_asksNothingBeyondGoalRowsAndTriggers() {
        goal(List.of());

        GoalRelationsResponse response = service.relations("g", null);

        assertThat(response.todos()).isEmpty();
        verify(projectRepository, never()).findIdAndNameByIdIn(any());
        verify(workflowRepository, never()).findIdAndNameByIdIn(any());
        verify(runRepository, never()).findRunStates(any());
        verify(findingTodoRepository, never()).findByTodoIdIn(any());
    }

    // ── Hilfen ────────────────────────────────────────────────────────────────

    private void goal(List<GoalRelationRow> rows) {
        when(goalRepository.existsById("g")).thenReturn(true);
        when(todoRepository.findGoalRelationRows("g")).thenReturn(rows);
    }

    private static TodoRelation byId(GoalRelationsResponse response, String id) {
        return response.todos().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private static RowBuilder row(String id) {
        return new RowBuilder(id);
    }

    /** Baut eine {@link GoalRelationRow}; ohne Angaben ein offenes MEDIUM-TODO eines Menschen. */
    private static final class RowBuilder {
        private final String id;
        private Todo.Priority priority = Todo.Priority.MEDIUM;
        private Integer rank;
        private String project;
        private String tags;
        private Todo.Source source = Todo.Source.USER;
        private String agent;
        private String runId;
        private String refType;
        private String refId;
        private String linked;
        private String fixRun;
        private int attempts;
        private Todo.ResolutionState state;

        RowBuilder(String id) { this.id = id; }
        RowBuilder priority(Todo.Priority value) { priority = value; return this; }
        RowBuilder rank(int value) { rank = value; return this; }
        RowBuilder project(String value) { project = value; return this; }
        RowBuilder tags(String... values) { tags = TodoMapper.listToJson(List.of(values)); return this; }
        RowBuilder source(Todo.Source value) { source = value; return this; }
        RowBuilder agent(String value) { agent = value; return this; }
        RowBuilder run(String value) { runId = value; return this; }
        RowBuilder ref(String type, String value) { refType = type; refId = value; return this; }
        RowBuilder linked(String value) { linked = value; return this; }
        RowBuilder fix(String run, int tries, Todo.ResolutionState value) { fixRun = run; attempts = tries; state = value; return this; }

        GoalRelationRow build() {
            return new GoalRelationRow(id, "Titel " + id, Todo.Status.OPEN, priority, project, tags, rank, T0,
                    source, agent, runId, refType, refId, linked, fixRun, attempts, state);
        }
    }
}
