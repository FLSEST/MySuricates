package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.metrics.RequestMetricsFactSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowContextDataServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepResultRepository stepResultRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private RequestMetricsFactSheet requestMetricsFactSheet;
    @Mock private ActivityLogService activityLogService;
    @Mock private GoalContextSource goalContextSource;
    @Mock private AuditContextSource auditContextSource;

    private WorkflowContextDataService service;

    @BeforeEach
    void setUp() {
        service = service(new PreviousReportVariable(runRepository, stepResultRepository), new OpenTodosVariable(todoRepository));
    }

    /** Der Einsammler mit allen fuenf Quellen in ihrer Reihenfolge; die ersten beiden austauschbar. */
    private WorkflowContextDataService service(PlatformVariableSource previousReport, PlatformVariableSource openTodos) {
        return new WorkflowContextDataService(List.of(previousReport, openTodos,
                new RequestMetricsVariable(requestMetricsFactSheet), new GoalVariables(goalContextSource, com.automation.modules.ModuleRegistry.allEnabled()),
                new AuditFindingsVariable(auditContextSource)), activityLogService);
    }

    /** Eine Quelle, deren Lader wirft — wie ein Ausfall mitten in der Abfrage. */
    private static PlatformVariableSource failing(String name) {
        return new PlatformVariableSource() {
            @Override public List<String> allNames() { return List.of(name); }
            @Override public List<String> names(Request request) { return List.of(name); }
            @Override public java.util.Map<String, String> load(Request request) { throw new IllegalStateException("kaputt"); }
        };
    }

    /** Eine Quelle mit festem Wert. */
    private static PlatformVariableSource fixed(String name, String value) {
        return new PlatformVariableSource() {
            @Override public List<String> allNames() { return List.of(name); }
            @Override public List<String> names(Request request) { return List.of(name); }
            @Override public java.util.Map<String, String> load(Request request) { return java.util.Map.of(name, value); }
        };
    }

    // --- platformVariableNames (TODO 2a0695ff) ------------------------------------------

    @Test
    void platformVariableNames_areEveryNameOfEverySource_whetherItAppliesToARunOrNot() {
        assertThat(service.platformVariableNames()).containsExactlyElementsOf(java.util.stream.Stream.concat(
                java.util.stream.Stream.of("previous_report", "open_todos", "request_metrics"),
                java.util.stream.Stream.concat(GoalContextSource.VARIABLES.stream(),
                        java.util.stream.Stream.of("audit_findings"))).toList());
        verify(auditContextSource, org.mockito.Mockito.never()).auditKey(any());
    }

    /**
     * TODO 2a0695ff: Eine Quelle setzt nur, was sie angemeldet hat. Ein fremder Name – hier ein Systemwert – wird
     * verworfen und laut gemeldet, statt als Plattform-Daten an den reservierten Namen vorbei gesetzt zu werden.
     */
    @Test
    void injectPlatformData_aValueTheSourceDidNotAnnounce_isDroppedAndReported() {
        PlatformVariableSource smuggler = new PlatformVariableSource() {
            @Override public List<String> allNames() { return List.of("smuggler_report"); }
            @Override public List<String> names(Request request) { return List.of("smuggler_report"); }
            @Override public java.util.Map<String, String> load(Request request) {
                return java.util.Map.of("smuggler_report", "angemeldet", "project_path", "/evil");
            }
        };
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        ctx.setVariable("project_path", "/p");

        new WorkflowContextDataService(List.of(smuggler), activityLogService).injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.getVariable("smuggler_report")).isEqualTo("angemeldet");
        assertThat(ctx.getVariable("project_path")).isEqualTo("/p");
        assertThat(ctx.isUntrusted("project_path")).as("bleibt der vertrauenswuerdige Systemwert").isFalse();
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), org.mockito.ArgumentMatchers.contains("'project_path'"),
                eq("WorkflowContextDataService"), eq("run-1"), eq("wf-1"));
    }

    @Test
    void platformVariableNames_areEveryOneOfThemASystemVariable_soNoneLandsInAStepDelta() {
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS).containsAll(service.platformVariableNames());
    }

    // --- injectPlatformData ------------------------------------------------------------

    @Test
    void injectPlatformData_setsBothVariables() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.getVariable("previous_report")).isEqualTo("(kein vorheriger Report vorhanden)");
        assertThat(ctx.getVariable("open_todos")).isEqualTo("(keine offenen TODOs)");
    }

    @Test
    void injectPlatformData_marksBothVariablesAsUntrusted() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.isUntrusted("previous_report")).isTrue();
        assertThat(ctx.isUntrusted("open_todos")).isTrue();
        // Platform data is injected into every run — it must not taint the run's own block outputs
        assertThat(ctx.hasUntrustedInput()).isFalse();
    }

    @Test
    void injectPlatformData_previousReportFromAnEarlierRun_isSingleQuotedInShellCommands() {
        // Cross-run laundering: the earlier run's output may carry attacker-controlled bytes
        String payload = "x; rm -rf / " + "a".repeat(250);
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("data").build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output(payload).build()));
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.resolveShellTemplate("echo {{ previous_report }}", false))
                .isEqualTo("echo '" + payload + "'");
    }

    @Test
    void injectPlatformData_openTodos_isSingleQuotedInShellCommands() {
        Todo t = Todo.builder().id("t-1").priority(Todo.Priority.LOW).title("Fix $(id) it").build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of(t));
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        String resolved = ctx.resolveShellTemplate("echo {{ open_todos }}", false);
        assertThat(resolved).startsWith("echo '<open_todos>").endsWith("</open_todos>'").contains("Fix $(id) it");
    }

    // --- injectPlatformData: request_metrics (TODO 843be7a5) ----------------------------
    //
    // Geprueft wurde die Kontextvariable bisher nur ueber die Vorlage: test-audit-facts.sh
    // rendert den Shell-Block mit einem Fixture-Wert. Das belegt die Vorlage, nicht die
    // Injektion -- und in genau dieser Luecke blieb am 2026-09-18 unbemerkt, dass der
    // Live-Workflow die Aenderung nie bekommen hatte (Run cd335b5c).

    /** Kontext ohne Vorgeschichte: die beiden anderen Lader liefern ihre Platzhalter. */
    private ExecutionContext injectWithoutHistory() {
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        service.injectPlatformData(ctx, "wf-1", "proj-1");
        return ctx;
    }

    @Test
    void injectPlatformData_setsRequestMetricsFromTheFactSheet() {
        when(requestMetricsFactSheet.render()).thenReturn(
                "FAKT request_metrics_routes_measured: 12\nFAKT request_metrics_slowest_p95_ms: 154");

        ExecutionContext ctx = injectWithoutHistory();

        assertThat(ctx.getVariable("request_metrics"))
                .as("die gemessenen Request-Kosten muessen im Kontext stehen, nicht nur in der Vorlage")
                .isEqualTo("FAKT request_metrics_routes_measured: 12\nFAKT request_metrics_slowest_p95_ms: 154");
    }

    @Test
    void injectPlatformData_passesTheExplicitGapThrough() {
        when(requestMetricsFactSheet.render()).thenReturn(
                "NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)");

        assertThat(injectWithoutHistory().getVariable("request_metrics"))
                .isEqualTo("NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)");
    }

    @Test
    void injectPlatformData_requestMetrics_isNeverBlank() {
        when(requestMetricsFactSheet.render()).thenReturn("   ");

        assertThat((String) injectWithoutHistory().getVariable("request_metrics"))
                .as("ein leerer Wert erreicht den Gate-Block als leere Messung und wuerde GREEN ergeben")
                .isNotBlank()
                .startsWith("NICHT GEMESSEN: request_metrics");
    }

    @Test
    void injectPlatformData_factSheetThrows_statesTheGapInsteadOfDroppingTheVariable() {
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));

        assertThat((String) injectWithoutHistory().getVariable("request_metrics"))
                .as("faellt die Messung aus, muss die Luecke dastehen — sonst sieht der Shell-Block"
                        + " den rohen Platzhalter")
                .startsWith("NICHT GEMESSEN: request_metrics")
                .contains("IllegalStateException");
    }

    @Test
    void injectPlatformData_factSheetFailure_doesNotCostTheOtherTwoVariables() {
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));

        ExecutionContext ctx = injectWithoutHistory();

        assertThat(ctx.getVariable("previous_report")).isEqualTo("(kein vorheriger Report vorhanden)");
        assertThat(ctx.getVariable("open_todos")).isEqualTo("(keine offenen TODOs)");
    }

    // --- injectPlatformData: jede Injektion mit eigenem Fehlerpfad (TODO 62d186b8) ------
    //
    // Der gemeinsame catch(Exception) um alle drei Injektionen hat den Ausfall von
    // request_metrics monatelang verdeckt: die Variable blieb abwesend, der Shell-Block sah
    // den rohen Platzhalter, und ein Gate-Block wertete den leeren Wert als GREEN. Die drei
    // Tests hier halten fest, dass kein Ausfall mehr einen anderen mitreisst -- und dass die
    // ausgefallene Variable dasteht statt zu fehlen.

    /** Die beiden je anderen Lader liefern ihre Platzhalter -- jeder Ausfall muss sie unberuehrt lassen. */
    private void assertUntouched(ExecutionContext ctx, String... names) {
        for (String name : names) {
            assertThat((String) ctx.getVariable(name))
                    .as("%s darf den Ausfall der anderen Injektion nicht mitbezahlen", name)
                    .isNotBlank()
                    .doesNotStartWith("NICHT GEMESSEN: " + name);
        }
    }

    @Test
    void injectPlatformData_previousReportLoaderThrows_statesTheGapInsteadOfDroppingTheVariable() {
        WorkflowContextDataService withFailingSource = service(failing("previous_report"), new OpenTodosVariable(todoRepository));
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 12");
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        withFailingSource.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat((String) ctx.getVariable("previous_report"))
                .as("faellt der Lader aus, muss die Luecke dastehen -- sonst sieht der Block den rohen Platzhalter")
                .startsWith("NICHT GEMESSEN: previous_report")
                .contains("IllegalStateException");
        assertUntouched(ctx, "open_todos", "request_metrics");
    }

    @Test
    void injectPlatformData_openTodosLoaderThrows_statesTheGapInsteadOfDroppingTheVariable() {
        WorkflowContextDataService withFailingSource = service(fixed("previous_report", "frueherer Report"), failing("open_todos"));
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 12");
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        withFailingSource.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat((String) ctx.getVariable("open_todos"))
                .startsWith("NICHT GEMESSEN: open_todos")
                .contains("IllegalStateException");
        assertUntouched(ctx, "previous_report", "request_metrics");
    }

    @Test
    void injectPlatformData_gapIsReportedToTheActivityLogWithTheRunId() {
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));

        injectWithoutHistory();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), message.capture(),
                eq("WorkflowContextDataService"), eq("run-1"), eq("wf-1"));
        assertThat(message.getValue())
                .as("die Luecke gehoert sichtbar an den Lauf, nicht nur ins Server-Log")
                .contains("request_metrics")
                .contains("IllegalStateException");
    }

    @Test
    void injectPlatformData_failingAssignment_doesNotCostTheOtherTwoVariables() {
        // Selbst wenn das Setzen selbst scheitert, duerfen die beiden anderen Injektionen laufen.
        ExecutionContext ctx = mock(ExecutionContext.class);
        doThrow(new IllegalStateException("kaputt"))
                .when(ctx).setUntrustedPlatformVariable(eq("previous_report"), any());
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 12");

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        verify(ctx).setUntrustedPlatformVariable("open_todos", "(keine offenen TODOs)");
        verify(ctx).setUntrustedPlatformVariable("request_metrics", "FAKT request_metrics_routes_measured: 12");
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), anyString(),
                eq("WorkflowContextDataService"), any(), eq("wf-1"));
    }

    @Test
    void injectPlatformData_failingActivityLog_doesNotCostTheOtherTwoVariables() {
        // Das Melden der Luecke darf nicht zu der Ausnahme werden, die die naechste Injektion frisst.
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));
        doThrow(new RuntimeException("activity log down")).when(activityLogService)
                .warn(any(), anyString(), anyString(), any(), any());
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        assertThatCode(() -> service.injectPlatformData(ctx, "wf-1", "proj-1")).doesNotThrowAnyException();

        assertThat((String) ctx.getVariable("request_metrics")).startsWith("NICHT GEMESSEN: request_metrics");
        assertUntouched(ctx, "previous_report", "open_todos");
    }

    @Test
    void requestMetrics_isASystemVariable_soItStaysOutOfEveryStepDelta() {
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS)
                .as("sonst landet das Faktenblatt im Context-Delta jedes Steps")
                .contains("request_metrics");
    }

    // --- injectPlatformData: goal_status + goal_guard_* (TODO 259f4f66) ----------------

    private ExecutionContext goalContext(Object goalId) {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        if (goalId != null) {
            ctx.setVariable("goal_id", goalId);
        }
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        return ctx;
    }

    @Test
    void injectPlatformData_withoutGoalId_setsNoGoalVariables() {
        ExecutionContext ctx = goalContext(null);

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        for (String name : GoalContextSource.VARIABLES) {
            assertThat(ctx.getVariables()).as(name).doesNotContainKey(name);
        }
        verify(goalContextSource, org.mockito.Mockito.never()).variables(anyString(), any());
    }

    @Test
    void injectPlatformData_blankGoalId_setsNoGoalVariables() {
        ExecutionContext ctx = goalContext("  ");

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.getVariables()).doesNotContainKey("goal_status");
        verify(goalContextSource, org.mockito.Mockito.never()).variables(anyString(), any());
    }

    @Test
    void injectPlatformData_withGoalId_setsEveryGoalVariable_asUntrustedPlatformData() {
        ExecutionContext ctx = goalContext("g-1");
        ctx.setVariable("goal_locale", "en");
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        GoalContextSource.VARIABLES.forEach(name -> values.put(name, "value of " + name));
        when(goalContextSource.variables("g-1", "en")).thenReturn(values);

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        for (String name : GoalContextSource.VARIABLES) {
            assertThat(ctx.getVariable(name)).as(name).isEqualTo("value of " + name);
            assertThat(ctx.isUntrusted(name)).as(name).isTrue();
        }
        assertThat(ctx.hasUntrustedInput()).isFalse();
    }

    @Test
    void injectPlatformData_goalIdIsReducedToIdCharacters() {
        ExecutionContext ctx = goalContext("g-1'; rm -rf /");
        when(goalContextSource.variables("g-1rm-rf", null)).thenReturn(java.util.Map.of());

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        verify(goalContextSource).variables("g-1rm-rf", null);
    }

    @Test
    void injectPlatformData_unknownGoal_statesTheGapInEveryGoalVariable_andWarns() {
        ExecutionContext ctx = goalContext("missing");
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 1");
        when(goalContextSource.variables("missing", null))
                .thenThrow(new com.automation.exception.ResourceNotFoundException("Goal", "missing"));

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        for (String name : GoalContextSource.VARIABLES) {
            assertThat((String) ctx.getVariable(name)).as(name)
                    .startsWith("NICHT GEMESSEN: " + name)
                    .contains("ResourceNotFoundException");
        }
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), org.mockito.ArgumentMatchers.contains("goal_status"),
                eq("WorkflowContextDataService"), eq("run-1"), eq("wf-1"));
        assertUntouched(ctx, "previous_report", "open_todos", "request_metrics");
    }

    @Test
    void goalVariables_areSystemVariables_andStayOutOfTheContextDelta() {
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS).containsAll(GoalContextSource.VARIABLES);
    }

    // --- injectPlatformData: audit_findings (TODO 3643beac) ------------------------------

    private static final java.util.Map<String, Object> AUDIT_META = java.util.Map.of("audit", java.util.Map.of(
            "enabled", true, "key", "project.security", "category", "security", "reportBlock", "report"));

    /** Kontext ohne Vorgeschichte, die Metadaten werden als Audit-Workflow erkannt. */
    private ExecutionContext auditContext() {
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        when(auditContextSource.auditKey(AUDIT_META)).thenReturn("project.security");
        return ExecutionContext.builder().workflowRunId("run-1").build();
    }

    @Test
    void injectPlatformData_auditWorkflow_setsAuditFindings_asUntrustedPlatformData() {
        ExecutionContext ctx = auditContext();
        when(auditContextSource.findings("proj-1", "project.security"))
                .thenReturn("<audit_findings>\nOPEN | HIGH | shell.injection | Foo $(id) | Titel\n</audit_findings>");

        service.injectPlatformData(ctx, "wf-1", "proj-1", AUDIT_META);

        assertThat((String) ctx.getVariable("audit_findings")).startsWith("<audit_findings>");
        assertThat(ctx.isUntrusted("audit_findings")).isTrue();
        // Wie open_todos: gesetzt faerbt die Variable den Lauf noch nicht, erst ihre Verwendung
        assertThat(ctx.hasUntrustedInput()).isFalse();
        assertThat(ctx.resolveShellTemplate("echo {{ audit_findings }}", false))
                .isEqualTo("echo '<audit_findings>\nOPEN | HIGH | shell.injection | Foo $(id) | Titel\n</audit_findings>'");
        assertThat(ctx.hasUntrustedInput()).as("in eine Shell-Zeile eingesetzt, faerbt sie den Lauf").isTrue();
    }

    @Test
    void injectPlatformData_withoutAuditMetadata_setsNoAuditFindings() {
        ExecutionContext normal = ExecutionContext.builder().workflowRunId("run-1").build();
        ExecutionContext shortForm = ExecutionContext.builder().workflowRunId("run-2").build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());
        java.util.Map<String, Object> normalMeta = java.util.Map.of("autoTodo", java.util.Map.of("enabled", true));

        service.injectPlatformData(normal, "wf-1", "proj-1", normalMeta);
        service.injectPlatformData(shortForm, "wf-1", "proj-1");

        assertThat(normal.getVariables()).doesNotContainKey("audit_findings");
        assertThat(shortForm.getVariables()).doesNotContainKey("audit_findings");
        verify(auditContextSource).auditKey(normalMeta);
        verify(auditContextSource, org.mockito.Mockito.never()).findings(any(), any());
    }

    @Test
    void injectPlatformData_auditFindingsLoaderThrows_statesTheGap_andWarnsWithTheRunId() {
        ExecutionContext ctx = auditContext();
        when(requestMetricsFactSheet.render()).thenReturn("FAKT request_metrics_routes_measured: 1");
        when(auditContextSource.findings("proj-1", "project.security"))
                .thenThrow(new IllegalStateException("kaputt"));

        service.injectPlatformData(ctx, "wf-1", "proj-1", AUDIT_META);

        assertThat((String) ctx.getVariable("audit_findings"))
                .as("faellt die Abfrage aus, muss die Luecke dastehen -- sonst sieht der Prompt den rohen Platzhalter")
                .isEqualTo("NICHT GEMESSEN: audit_findings (nicht verfügbar: IllegalStateException)");
        assertThat(ctx.isUntrusted("audit_findings")).isTrue();
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), org.mockito.ArgumentMatchers.contains("audit_findings"),
                eq("WorkflowContextDataService"), eq("run-1"), eq("wf-1"));
        assertUntouched(ctx, "previous_report", "open_todos", "request_metrics");
    }

    @Test
    void injectPlatformData_otherLoaderThrows_doesNotCostTheAuditFindings() {
        ExecutionContext ctx = auditContext();
        when(requestMetricsFactSheet.render()).thenThrow(new IllegalStateException("kaputt"));
        when(auditContextSource.findings("proj-1", "project.security")).thenReturn("(keine bekannten Befunde)");

        service.injectPlatformData(ctx, "wf-1", "proj-1", AUDIT_META);

        assertThat(ctx.getVariable("audit_findings")).isEqualTo("(keine bekannten Befunde)");
        assertThat((String) ctx.getVariable("request_metrics")).startsWith("NICHT GEMESSEN: request_metrics");
    }

    @Test
    void auditFindings_isASystemVariable_soItStaysOutOfEveryStepDelta() {
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS)
                .as("sonst landet der Befundblock im Context-Delta jedes Steps")
                .contains("audit_findings");
    }
}
