package com.automation.service.goal;

import com.automation.dto.projection.GoalRelationRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.dto.projection.TodoTriggerMatchRow;
import com.automation.dto.response.GoalRelationsResponse.Origin;
import com.automation.dto.response.GoalRelationsResponse.OriginKind;
import com.automation.dto.response.GoalRelationsResponse.Result;
import com.automation.dto.response.GoalRelationsResponse.TodoRelation;
import com.automation.dto.response.GoalRelationsResponse.Worker;
import com.automation.dto.response.GoalRelationsResponse.WorkerKind;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.mapper.TodoMapper;
import com.automation.service.goal.GoalRelationAssembler.Lookups;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relationskarte ohne Service und Repositories (TODO c8a11b7a): was aus einer Zeile und den
 * Nachschlagetabellen wird. Den Weg durch {@code GoalRelationsService} samt Buendelung prueft
 * {@code GoalRelationsServiceTest}; hier stehen die Randfaelle der Zuordnung.
 */
class GoalRelationAssemblerTest {

    private static final Instant T0 = Instant.parse("2026-09-25T08:00:00Z");

    @Test
    void toRelation_mapsTheRow_projectNameOnlyForAKnownProject() {
        Lookups lookups = new Lookups(Map.of("p1", "Launch"), Map.of(), Map.of(), Map.of(), List.of());

        TodoRelation relation = GoalRelationAssembler.toRelation(
                row("t1").project("p1").tags("release", "docs").rank(2).priority(Todo.Priority.HIGH).build(), true, lookups);
        TodoRelation projectGone = GoalRelationAssembler.toRelation(row("t2").project("p-gone").build(), false, lookups);
        TodoRelation bare = GoalRelationAssembler.toRelation(row("t3").build(), false, lookups);

        assertThat(relation.id()).isEqualTo("t1");
        assertThat(relation.title()).isEqualTo("Titel t1");
        assertThat(relation.status()).isEqualTo(Todo.Status.OPEN);
        assertThat(relation.priority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(relation.projectId()).isEqualTo("p1");
        assertThat(relation.projectName()).isEqualTo("Launch");
        assertThat(relation.tags()).containsExactly("release", "docs");
        assertThat(relation.goalRank()).isEqualTo(2);
        assertThat(relation.candidate()).isTrue();
        assertThat(relation.origin().kind()).isEqualTo(OriginKind.USER);
        assertThat(relation.workers()).isEmpty();
        assertThat(relation.result()).as("nie bearbeitet: kein Ergebnis").isNull();
        assertThat(projectGone.projectName()).as("Projekt geloescht").isNull();
        assertThat(bare.projectName()).isNull();
        assertThat(bare.tags()).as("ohne Tags eine leere Liste, nicht null").isEmpty();
        assertThat(bare.candidate()).isFalse();
    }

    @Test
    void referencedWorkflowId_onlyForWorkflowRunReferences() {
        assertThat(GoalRelationAssembler.referencedWorkflowId(row("a").ref("workflow_run", "wf-1").build())).isEqualTo("wf-1");
        assertThat(GoalRelationAssembler.referencedWorkflowId(row("b").ref("audit_finding", "f-1").build())).isNull();
        assertThat(GoalRelationAssembler.referencedWorkflowId(row("c").build())).isNull();
    }

    @Test
    void originKind_firstMatchWins_inTheDocumentedOrder() {
        Lookups lookups = new Lookups(Map.of(), Map.of(), Map.of(), Map.of("audit-by-table", List.of("f-9")), List.of());

        assertThat(kind(row("audit-by-table").run("run-1").source(Todo.Source.AI).build(), lookups))
                .as("Befund-Tabelle allein genuegt und schlaegt Lauf und Quelle").isEqualTo(OriginKind.AUDIT);
        assertThat(kind(row("audit-by-reference").ref("audit_finding", "f-5").source(Todo.Source.WORKFLOW)
                .run("run-4").build(), lookups))
                .as("Audit-Referenz allein, ohne Tabelleneintrag, genuegt ebenso").isEqualTo(OriginKind.AUDIT);
        assertThat(kind(row("health-with-run").ref("health_check", "disk").run("run-2").build(), lookups))
                .as("Health vor Lauf").isEqualTo(OriginKind.HEALTH);
        assertThat(kind(row("reference-only").ref("workflow_run", "wf-1").build(), lookups))
                .as("Workflow-Referenz ohne Lauf, Quelle USER").isEqualTo(OriginKind.WORKFLOW_RUN);
        assertThat(kind(row("workflow-source").source(Todo.Source.WORKFLOW).build(), lookups))
                .as("Quelle WORKFLOW ohne Lauf und Referenz").isEqualTo(OriginKind.WORKFLOW_RUN);
        assertThat(kind(row("ai-with-run").source(Todo.Source.AI).run("run-3").build(), lookups))
                .as("Lauf vor Agent").isEqualTo(OriginKind.WORKFLOW_RUN);
        assertThat(kind(row("ai").source(Todo.Source.AI).build(), lookups)).isEqualTo(OriginKind.AGENT);
        assertThat(kind(row("user").build(), lookups)).isEqualTo(OriginKind.USER);
    }

    @Test
    void originFindings_referenceFirst_withoutDuplicates_emptyOutsideAudit() {
        Lookups lookups = new Lookups(Map.of(), Map.of(), Map.of(),
                Map.of("both", List.of("f-2", "f-1"), "table-only", List.of("f-3")), List.of());

        assertThat(GoalRelationAssembler.originOf(row("both").ref("audit_finding", "f-1").build(), lookups).findingIds())
                .containsExactly("f-1", "f-2");
        assertThat(GoalRelationAssembler.originOf(row("table-only").ref("audit_finding", null).build(), lookups).findingIds())
                .as("Audit-Referenz ohne ID: nur die Tabelle").containsExactly("f-3");
        assertThat(GoalRelationAssembler.originOf(row("reference-only").ref("audit_finding", "f-5").build(), lookups).findingIds())
                .as("Audit nur ueber die Referenz, ohne Tabelleneintrag").containsExactly("f-5");
        assertThat(GoalRelationAssembler.originOf(row("user").build(), lookups).findingIds()).isEmpty();
    }

    @Test
    void originWorkflow_fromTheRun_elseFromTheReference_nameCurrentElseFromTheRun() {
        Lookups lookups = new Lookups(Map.of(),
                Map.of("wf-run", "Aktueller Name", "wf-ref", "Referenzierter Workflow"),
                Map.of("run-live", new RunStateRow("run-live", "wf-run", "Name beim Lauf", RunStatus.COMPLETED, T0, T0),
                        "run-wf-gone", new RunStateRow("run-wf-gone", "wf-gone", "Name beim Lauf", RunStatus.FAILED, T0, T0),
                        "run-no-wf", new RunStateRow("run-no-wf", null, "Name beim Lauf", RunStatus.CANCELLED, T0, T0)),
                Map.of(), List.of());

        Origin runWins = GoalRelationAssembler.originOf(row("a").run("run-live").ref("workflow_run", "wf-ref").build(), lookups);
        assertThat(runWins.workflowId()).as("Workflow des Laufs vor der Referenz").isEqualTo("wf-run");
        assertThat(runWins.workflowName()).as("aktueller Name vor dem beim Lauf").isEqualTo("Aktueller Name");
        assertThat(runWins.runId()).isEqualTo("run-live");
        assertThat(runWins.runStatus()).isEqualTo(RunStatus.COMPLETED);

        Origin workflowGone = GoalRelationAssembler.originOf(row("b").run("run-wf-gone").build(), lookups);
        assertThat(workflowGone.workflowId()).isEqualTo("wf-gone");
        assertThat(workflowGone.workflowName()).as("Workflow geloescht: Name beim Lauf").isEqualTo("Name beim Lauf");

        Origin runWithoutWorkflow = GoalRelationAssembler.originOf(
                row("c").run("run-no-wf").ref("workflow_run", "wf-ref").build(), lookups);
        assertThat(runWithoutWorkflow.workflowId()).isEqualTo("wf-ref");
        assertThat(runWithoutWorkflow.workflowName()).isEqualTo("Referenzierter Workflow");
        assertThat(runWithoutWorkflow.runStatus()).isEqualTo(RunStatus.CANCELLED);

        Origin runGone = GoalRelationAssembler.originOf(row("d").run("run-deleted").ref("workflow_run", "wf-ref").build(), lookups);
        assertThat(runGone.workflowId()).isEqualTo("wf-ref");
        assertThat(runGone.runId()).as("die ID bleibt, auch wenn der Lauf fehlt").isEqualTo("run-deleted");
        assertThat(runGone.runStatus()).isNull();

        Origin none = GoalRelationAssembler.originOf(row("e").agent("claude-code").build(), lookups);
        assertThat(none.workflowId()).isNull();
        assertThat(none.workflowName()).isNull();
        assertThat(none.runId()).isNull();
        assertThat(none.agent()).isEqualTo("claude-code");
    }

    @Test
    void workers_linkedFirst_thenMatchingTriggersInOrder_projectAndSourceFiltersApply() {
        Lookups lookups = new Lookups(Map.of(),
                Map.of("wf-fix", "Fix", "wf-a", "A", "wf-b", "B", "wf-c", "C", "wf-d", "D"),
                Map.of(), Map.of(), List.of(
                        new TodoTriggerMatchRow("tr-other-project", null, "p2", null, null, "wf-a", "Anderes Projekt"),
                        new TodoTriggerMatchRow("tr-project", "backend", "p1", null, null, "wf-b", "Projekt"),
                        new TodoTriggerMatchRow("tr-source-ai", null, null, "AI", null, "wf-c", "Nur KI"),
                        new TodoTriggerMatchRow("tr-target-gone", null, null, null, null, "wf-deleted", "Ziel weg"),
                        new TodoTriggerMatchRow("tr-any", null, null, null, null, "wf-d", null)));

        List<Worker> workers = GoalRelationAssembler.workersOf(row("t").tags("backend").project("p1")
                .source(Todo.Source.WORKFLOW).priority(Todo.Priority.HIGH).linked("wf-fix").build(), lookups);
        List<Worker> linkedGone = GoalRelationAssembler.workersOf(row("u").linked("wf-gone").build(), lookups);

        assertThat(workers).as("tr-target-gone passt, aber sein Ziel-Workflow ist geloescht: faellt weg").containsExactly(
                new Worker(WorkerKind.LINKED, "wf-fix", "Fix", null, null),
                new Worker(WorkerKind.TRIGGER, "wf-b", "B", "tr-project", "Projekt"),
                new Worker(WorkerKind.TRIGGER, "wf-d", "D", "tr-any", null));
        assertThat(linkedGone).as("geloeschter Fix-Workflow faellt weg, der filterlose Trigger bleibt")
                .containsExactly(new Worker(WorkerKind.TRIGGER, "wf-d", "D", "tr-any", null));
    }

    @Test
    void result_absentWithoutAnyFix_presentFromAttemptsAlone_runFieldsOnlyForAKnownRun() {
        Lookups lookups = new Lookups(Map.of(), Map.of(),
                Map.of("run-live", new RunStateRow("run-live", "wf-fix", "Fix", RunStatus.RUNNING, T0, null),
                        "run-done", new RunStateRow("run-done", "wf-fix", "Fix", RunStatus.COMPLETED, T0, T0.plusSeconds(90))),
                Map.of(), List.of());

        assertThat(GoalRelationAssembler.resultOf(row("never").build(), lookups)).isNull();
        assertThat(GoalRelationAssembler.resultOf(row("linked-never-ran").fix(null, 0, Todo.ResolutionState.IDLE).build(), lookups))
                .as("05d4bfe5: verknuepfter Fix-Workflow (IDLE), der nie lief, hat noch kein Ergebnis")
                .isNull();
        assertThat(GoalRelationAssembler.resultOf(row("run-deleted-later").fix(null, 2, Todo.ResolutionState.IDLE).build(), lookups))
                .as("Fix-Lauf geloescht (RunDeletionService setzt IDLE): Versuche bleiben sichtbar")
                .isEqualTo(new Result(null, null, null, null, 2, Todo.ResolutionState.IDLE));
        assertThat(GoalRelationAssembler.resultOf(row("unknown-run").fix("run-x", 1, Todo.ResolutionState.FAILED).build(), lookups))
                .isEqualTo(new Result("run-x", null, null, null, 1, Todo.ResolutionState.FAILED));
        assertThat(GoalRelationAssembler.resultOf(row("running").fix("run-live", 1, Todo.ResolutionState.RUNNING).build(), lookups))
                .as("laeuft noch: Startzeit")
                .isEqualTo(new Result("run-live", "wf-fix", RunStatus.RUNNING, T0, 1, Todo.ResolutionState.RUNNING));
        assertThat(GoalRelationAssembler.resultOf(row("done").fix("run-done", 2, Todo.ResolutionState.SUCCEEDED).build(), lookups))
                .as("beendet: Endzeit vor Startzeit")
                .isEqualTo(new Result("run-done", "wf-fix", RunStatus.COMPLETED, T0.plusSeconds(90), 2,
                        Todo.ResolutionState.SUCCEEDED));
    }

    @Test
    void workOrder_rank_thenPriority_thenAge_unrankedLast() {
        List<GoalRelationRow> scrambled = List.of(
                row("unranked-low-new").priority(Todo.Priority.LOW).created(T0.plusSeconds(20)).build(),
                row("rank-2").rank(2).priority(Todo.Priority.LOW).created(T0).build(),
                row("unranked-critical").priority(Todo.Priority.CRITICAL).created(T0.plusSeconds(30)).build(),
                row("unranked-low-old").priority(Todo.Priority.LOW).created(T0.plusSeconds(10)).build(),
                row("rank-1").rank(1).priority(Todo.Priority.LOW).created(T0.plusSeconds(40)).build());

        List<String> order = scrambled.stream().sorted(GoalRelationAssembler.WORK_ORDER).map(GoalRelationRow::id).toList();

        assertThat(order).containsExactly("rank-1", "rank-2", "unranked-critical", "unranked-low-old", "unranked-low-new");
    }

    private static OriginKind kind(GoalRelationRow row, Lookups lookups) {
        return GoalRelationAssembler.originOf(row, lookups).kind();
    }

    private static RowBuilder row(String id) {
        return new RowBuilder(id);
    }

    /** Baut eine {@link GoalRelationRow}; ohne Angaben ein offenes MEDIUM-TODO eines Menschen. */
    private static final class RowBuilder {
        private final String id;
        private Todo.Priority priority = Todo.Priority.MEDIUM;
        private Integer rank;
        private Instant createdAt = T0;
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
        RowBuilder created(Instant value) { createdAt = value; return this; }
        RowBuilder project(String value) { project = value; return this; }
        RowBuilder tags(String... values) { tags = TodoMapper.listToJson(List.of(values)); return this; }
        RowBuilder source(Todo.Source value) { source = value; return this; }
        RowBuilder agent(String value) { agent = value; return this; }
        RowBuilder run(String value) { runId = value; return this; }
        RowBuilder ref(String type, String value) { refType = type; refId = value; return this; }
        RowBuilder linked(String value) { linked = value; return this; }
        RowBuilder fix(String run, int tries, Todo.ResolutionState resolution) {
            fixRun = run; attempts = tries; state = resolution; return this;
        }

        GoalRelationRow build() {
            return new GoalRelationRow(id, "Titel " + id, Todo.Status.OPEN, priority, project, tags, rank, createdAt,
                    source, agent, runId, refType, refId, linked, fixRun, attempts, state);
        }
    }
}
