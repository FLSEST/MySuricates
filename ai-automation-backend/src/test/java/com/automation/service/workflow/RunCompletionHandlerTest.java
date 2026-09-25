package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.automation.service.dashboard.DashboardWidgetResultParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RunCompletionHandlerTest {

    @Mock private TemplateVerificationService templateVerificationService;
    @Mock private ActivityLogService activityLogService;

    @InjectMocks private RunCompletionHandler handler;

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ExecutionContext.builder().workflowRunId("run-1").build();
    }

    /** Builds a run with one COMPLETED step per output, in order. */
    private static WorkflowRun run(RunStatus status, String... stepOutputs) {
        List<StepResult> steps = new ArrayList<>();
        for (int i = 0; i < stepOutputs.length; i++) {
            steps.add(StepResult.builder().stepIndex(i).status(StepStatus.COMPLETED).output(stepOutputs[i]).build());
        }
        return WorkflowRun.builder().id("run-1").status(status).steps(steps).build();
    }

    @Test
    void complete_runningRun_becomesCompleted_andLogsInfo() {
        WorkflowRun run = run(RunStatus.RUNNING, "plain output");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(activityLogService).info(eq(LogCategory.EXECUTION), contains("completed"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
        verifyNoInteractions(templateVerificationService);
        assertThat(ctx.getRunMetadata()).doesNotContainKeys("gate_status", "todo_items");
    }

    @Test
    void failedTechnicalCheckRemainsVisibleAfterContinuingToOptimisticReport() {
        WorkflowRun run = run(RunStatus.RUNNING, "gate_status: RED", "gate_status: GREEN");
        run.getSteps().getFirst().setStatus(StepStatus.FAILED);
        run.getSteps().getFirst().setMetadata(Map.of("checkedCommand", true, "exitCode", 7));

        handler.complete(run, ctx, "wf-1", "Demo", "template-1", "proj-1", List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
        assertThat(ctx.getRunMetadata()).containsEntry("gate_status", "RED");
        verifyNoInteractions(templateVerificationService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_highestGateWins_andSummaryAggregatesAllGates() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "gate_status: GREEN\ngate_category: tests",
                "gate_status: RED\ngate_category: security\ngate_description: leaked key",
                "gate_status: YELLOW\ngate_category: lint");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "RED")
                .containsEntry("gate_category", "security")
                .containsEntry("gate_description", "leaked key");
        Map<String, Object> summary = (Map<String, Object>) ctx.getRunMetadata().get("gate_summary");
        assertThat(summary).containsEntry("highest", "RED");
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("counts_by_status");
        assertThat(counts).containsEntry("GREEN", 1).containsEntry("RED", 1).containsEntry("YELLOW", 1);
        assertThat((List<Map<String, Object>>) summary.get("items")).hasSize(3);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), contains("critical findings"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    // --- Gleichstand (TODO 2aa9ddd5) -------------------------------------------------------

    /** Der Kopf, den CheckedCommandGate einem erfolgreichen checked_command voranstellt. */
    private static String checkedCommandOutput(String category, int exitCode, String output) {
        return "gate_status: " + (exitCode == 0 ? "GREEN" : "RED") + "\ngate_category: " + category
                + "\ngate_description: Command exit " + exitCode + "\n\n" + output;
    }

    /**
     * Der Health-Check vom 25.09.: alle Schritte GREEN, der erste ein checked_command. Sein synthetisches
     * „Command exit 0“ stand seit dem 14.09. in jedem Report statt des Urteils am Ende.
     */
    @Test
    @SuppressWarnings("unchecked")
    void complete_allGreen_theClosingVerdictSpeaksForTheRun_notTheFirstChecksCommandExit() {
        WorkflowRun run = run(RunStatus.RUNNING,
                checkedCommandOutput("backend-test-failure", 0, "mvn test: exit 0"),
                checkedCommandOutput("frontend-build-failure", 0, "npm run build: exit 0"),
                "### Scorecard\n**Gesamtscore: 9.0 / 10**\n\ngate_status: GREEN\ngate_category: project-health\n"
                        + "gate_description: Score 9.0/10, Trend gleich",
                "Health Check: GUT\ngate_status: GREEN\ngate_category: project-health\n"
                        + "gate_description: Gesundheit gut, Faktenblatt vollstaendig");
        run.getSteps().get(0).setMetadata(Map.of("checkedCommand", true, "exitCode", 0));
        run.getSteps().get(1).setMetadata(Map.of("checkedCommand", true, "exitCode", 0));

        handler.complete(run, ctx, "wf-1", "Health", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "GREEN")
                .containsEntry("gate_category", "project-health")
                .containsEntry("gate_description", "Gesundheit gut, Faktenblatt vollstaendig");
        Map<String, Object> summary = (Map<String, Object>) ctx.getRunMetadata().get("gate_summary");
        assertThat((List<Map<String, Object>>) summary.get("items")).as("jedes Gate bleibt in der Zusammenfassung")
                .extracting(item -> item.get("description"))
                .containsExactly("Command exit 0", "Command exit 0", "Score 9.0/10, Trend gleich",
                        "Gesundheit gut, Faktenblatt vollstaendig");
    }

    /**
     * Bei RED und YELLOW bleibt der erste: die Ursache. Nach seiner Kategorie heisst und findet sich das
     * AutoTodo-Incident ({@code GateStatusResolver} liest genau diese Metadaten) – ein abschliessendes Urteil
     * darf es nicht umbenennen.
     */
    @Test
    void complete_equalRedOrYellow_theFirstGateKeepsItsCategory_forTheIncident() {
        WorkflowRun red = run(RunStatus.RUNNING,
                checkedCommandOutput("backend-test-failure", 1, "mvn test: exit 1"),
                "Build Gate: FAILED\ngate_status: RED\ngate_category: mysuricates-build\n"
                        + "gate_description: MySuricates Build Gate fehlgeschlagen");
        WorkflowRun yellow = run(RunStatus.RUNNING,
                "gate_status: YELLOW\ngate_category: lint\ngate_description: 2 Warnungen",
                "gate_status: YELLOW\ngate_category: summary\ngate_description: Ausbaufaehig");
        ExecutionContext yellowCtx = ExecutionContext.builder().workflowRunId("run-2").build();

        handler.complete(red, ctx, "wf-1", "Build Gate", null, "proj-1", List.of());
        handler.complete(yellow, yellowCtx, "wf-1", "Build Gate", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "RED")
                .containsEntry("gate_category", "backend-test-failure")
                .containsEntry("gate_description", "Command exit 1");
        assertThat(yellowCtx.getRunMetadata())
                .containsEntry("gate_category", "lint")
                .containsEntry("gate_description", "2 Warnungen");
    }

    @Test
    void complete_laterGreenWithoutDescription_doesNotReplaceOneThatHasIt() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "gate_status: GREEN\ngate_category: tests\ngate_description: 200 Tests gruen",
                "gate_status: GREEN\ngate_category: verdict");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_category", "tests")
                .containsEntry("gate_description", "200 Tests gruen");
    }

    /** Das synthetische „Command exit 0“ sagt nur, dass der Befehl lief – auch hinter dem Urteil verdraengt es das nicht. */
    @Test
    void complete_aChecksCommandExitAfterTheVerdict_doesNotReplaceIt() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "Health Check: GUT\ngate_status: GREEN\ngate_category: project-health\n"
                        + "gate_description: Gesundheit gut, Faktenblatt vollstaendig",
                checkedCommandOutput("frontend-lint-failure", 0, "npm run lint: exit 0"));
        run.getSteps().get(1).setMetadata(Map.of("checkedCommand", true, "exitCode", 0));

        handler.complete(run, ctx, "wf-1", "Health", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_category", "project-health")
                .containsEntry("gate_description", "Gesundheit gut, Faktenblatt vollstaendig");
    }

    /**
     * Ein spaeteres Urteil ohne eigene Kategorie behaelt die bisherige – sonst setzte GateStatusResolver „success“
     * und benennte ein track-TODO samt {@code category:}-Tag um.
     */
    @Test
    void complete_laterGreenWithoutCategory_keepsTheCategorySoFar() {
        WorkflowRun run = run(RunStatus.RUNNING,
                checkedCommandOutput("backend-test-failure", 0, "mvn test: exit 0"),
                "gate_status: GREEN\ngate_description: Alles gut");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_category", "backend-test-failure")
                .containsEntry("gate_description", "Alles gut");
    }

    @Test
    void complete_yellowGate_logsWarning_butRunIsCompleted() {
        WorkflowRun run = run(RunStatus.RUNNING, "gate_status: YELLOW\ngate_category: lint");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), contains("with warnings"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_todoMarkers_collectedIntoRunMetadata() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "todo_item: Fix login | HIGH | backend,bug | Login breaks on Safari",
                "todo_item: Tidy docs | LOW | documentation");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        List<Map<String, Object>> items = (List<Map<String, Object>>) ctx.getRunMetadata().get("todo_items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("title", "Fix login").containsEntry("priority", "HIGH")
                .containsEntry("description", "Login breaks on Safari");
        assertThat(items.get(1)).containsEntry("title", "Tidy docs").doesNotContainKey("description");
    }

    @Test
    void complete_withTemplateId_recordsVerification_andSwallowsFailures() {
        WorkflowRun run = run(RunStatus.RUNNING, "ok");
        doThrow(new RuntimeException("boom")).when(templateVerificationService)
                .recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        assertThatCode(() -> handler.complete(run, ctx, "wf-1", "Demo", "tpl-1", "proj-1", List.of()))
                .doesNotThrowAnyException();

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(templateVerificationService).recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");
    }

    @Test
    void complete_blankTemplateId_doesNotRecordVerification() {
        WorkflowRun run = run(RunStatus.RUNNING, "ok");

        handler.complete(run, ctx, "wf-1", "Demo", "   ", "proj-1", List.of());

        verifyNoInteractions(templateVerificationService);
    }

    @Test
    void complete_failedRun_logsError() {
        WorkflowRun run = run(RunStatus.FAILED, "boom");

        handler.complete(run, ctx, "wf-1", "Demo", "tpl-1", "proj-1", List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        verify(activityLogService).error(eq(LogCategory.EXECUTION), contains("failed"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
        verifyNoInteractions(templateVerificationService);
    }

    @Test
    void complete_cancelledRun_isLeftUntouched() {
        WorkflowRun run = run(RunStatus.CANCELLED, "partial");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verifyNoInteractions(templateVerificationService, activityLogService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_derivedOutputSteps_areNotParsedForMarkers() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "gate_status: RED\ngate_category: tests\ntodo_item: Fix A | HIGH | bug | desc",
                "## backend_tests — FAILED\n\ngate_status: RED\ngate_category: tests\ntodo_item: Fix A | HIGH | bug | desc");
        run.getSteps().get(1).setMetadata(Map.of("derivedOutput", true));

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        Map<String, Object> summary = (Map<String, Object>) ctx.getRunMetadata().get("gate_summary");
        assertThat((List<Map<String, Object>>) summary.get("items")).hasSize(1);
        assertThat((List<?>) ctx.getRunMetadata().get("todo_items")).hasSize(1);
    }

    /** A sub-workflow step hands the child's last output up; its markers belong to the child run only (6e5d6e2c). */
    @Test
    @SuppressWarnings("unchecked")
    void complete_derivedSubWorkflowOutput_keepsOnlyTheOwnStepsMarkers() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "gate_status: RED\ngate_category: tests\ntodo_item: Parent finding | HIGH | bug | own desc",
                "gate_status: RED\ngate_category: child\ntodo_item: Child finding | HIGH | bug | child desc");
        run.getSteps().get(1).setMetadata(Map.of("derivedOutput", true, "subRunId", "child-run-1"));

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        Map<String, Object> summary = (Map<String, Object>) ctx.getRunMetadata().get("gate_summary");
        assertThat((List<Map<String, Object>>) summary.get("items")).hasSize(1);
        assertThat((Map<String, Integer>) summary.get("counts_by_status")).containsExactly(Map.entry("RED", 1));
        assertThat((List<Map<String, Object>>) ctx.getRunMetadata().get("todo_items"))
                .extracting(item -> item.get("title"))
                .containsExactly("Parent finding");
    }

    @Test
    void storesTheDashboardWidgetContractOfTheLastReportingStep() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "dashboard_widget: {\"status\":\"GREEN\",\"title\":\"early\"}",
                "dashboard_widget: {\"status\":\"RED\",\"title\":\"final\",\"items\":[\"<b>text</b>\"]}");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.ERROR_METADATA_KEY);
        assertThat(ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY))
                .isEqualTo(Map.of("status", "RED", "title", "final", "items", List.of("<b>text</b>")));
    }

    @Test
    void marksABrokenMarkerAsInvalidWithoutEchoingTheOutput() {
        WorkflowRun run = run(RunStatus.RUNNING, "dashboard_widget: {\"status\":\"PINK\",\"secret\":\"key-1\"}");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(ctx.getRunMetadata().get(DashboardWidgetResultParser.ERROR_METADATA_KEY)).isEqualTo("INVALID");
    }

    @Test
    void ignoresMarkersInDerivedOutputAndRunsWithoutOne() {
        WorkflowRun compressed = run(RunStatus.RUNNING,
                "dashboard_widget: {\"status\":\"GREEN\"}", "dashboard_widget: {\"status\":\"RED\"}");
        compressed.getSteps().get(1).setMetadata(Map.of("derivedOutput", true));

        handler.complete(compressed, ctx, "wf-1", "Demo", null, "proj-1", List.of());
        assertThat(DashboardWidgetResultParser.fromMetadata(
                ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY)).result().status().name())
                .isEqualTo("GREEN");

        ExecutionContext plain = ExecutionContext.builder().workflowRunId("run-2").build();
        handler.complete(run(RunStatus.RUNNING, "nothing to report"), plain, "wf-1", "Demo", null, "proj-1", List.of());
        assertThat(plain.getRunMetadata()).doesNotContainKeys(
                DashboardWidgetResultParser.METADATA_KEY, DashboardWidgetResultParser.ERROR_METADATA_KEY);
    }

    /**
     * A dashboard-report block writes the contract into run metadata before the marker scan ever
     * runs; a later step's marker must not be allowed to overwrite it (TODO f4231dc9 — a later AI
     * step must not be able to overwrite a deliberately configured report by printing a marker,
     * which would be prompt injection with a visible dashboard effect).
     */
    @Test
    void aBlockWrittenReportWinsOverAMarkerInAnyStepOutput() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "dashboard_widget: {\"status\":\"RED\",\"title\":\"from marker\"}");
        // What a dashboard-report block would have written during the run:
        ctx.setRunMetadata(DashboardWidgetResultParser.METADATA_KEY,
                Map.of("status", "GREEN", "title", "from block"));

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        Object stored = ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(DashboardWidgetResultParser.fromMetadata(stored).result().title()).isEqualTo("from block");
        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.ERROR_METADATA_KEY);
    }

    @Test
    void aBrokenLaterMarkerCannotInvalidateABlockWrittenReport() {
        WorkflowRun run = run(RunStatus.RUNNING, "dashboard_widget: {\"status\":\"PINK\"}");
        ctx.setRunMetadata(DashboardWidgetResultParser.METADATA_KEY, Map.of("status", "GREEN"));

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.ERROR_METADATA_KEY);
        assertThat(DashboardWidgetResultParser
                .fromMetadata(ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY))
                .result().status().name()).isEqualTo("GREEN");
    }

    @Test
    void withoutABlockWrittenReportTheMarkerStillDecides() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "dashboard_widget: {\"status\":\"RED\",\"title\":\"from marker\"}");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of());

        Object stored = ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(DashboardWidgetResultParser.fromMetadata(stored).result().title()).isEqualTo("from marker");
    }

    /** Ein Block, dessen Prompt ein Gate-Urteil bestellt (TODO 63ecc124). */
    private static List<BlockDefinition> blocksOrderingAGate() {
        BlockDefinition block = new BlockDefinition();
        block.setType("simple");
        block.setConfig(Map.of("prompt", "Pruefe den Stand und schliesse mit gate_status: GREEN|YELLOW|RED"));
        return List.of(block);
    }

    @Test
    void bestelltesAberAusgebliebenesUrteilWirdGelb() {
        WorkflowRun run = run(RunStatus.RUNNING, "Ich habe alles geprueft, sieht gut aus.");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", blocksOrderingAGate());

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "YELLOW")
                .containsEntry("gate_category", RunCompletionHandler.GATE_MISSING_CATEGORY);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), contains("warnings"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    @Test
    void geliefertesUrteilSchlaegtDieErwartung() {
        WorkflowRun run = run(RunStatus.RUNNING, "gate_status: GREEN\ngate_category: tests");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", blocksOrderingAGate());

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "GREEN")
                .containsEntry("gate_category", "tests");
    }

    @Test
    void ohneBestellungBleibtEinLaufOhneUrteilUnberuehrt() {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setConfig(Map.of("command", "echo fertig"));
        WorkflowRun run = run(RunStatus.RUNNING, "fertig");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", List.of(block));

        assertThat(ctx.getRunMetadata()).doesNotContainKey("gate_status");
        verify(activityLogService).info(eq(LogCategory.EXECUTION), contains("completed"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    /** Ein gescheiterter Lauf trägt sein Urteil über den Laufstatus — nicht zusätzlich gelb färben. */
    @Test
    void gescheiterterLaufBekommtKeinGateMissing() {
        WorkflowRun run = run(RunStatus.FAILED, "abgebrochen");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1", blocksOrderingAGate());

        assertThat(ctx.getRunMetadata()).doesNotContainKey("gate_status");
    }
}
