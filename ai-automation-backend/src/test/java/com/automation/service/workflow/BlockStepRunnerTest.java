package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.SwitchBlockExecutor;
import com.automation.executor.compress.CompressionSources;
import com.automation.repository.ContainerRepository;
import com.automation.repository.StepResultRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutput;
import com.automation.service.live.LiveOutputSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockStepRunnerTest {

    @Mock private StepResultRepository stepResultRepository;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private ActivityLogService activityLogService;
    @Mock private ContainerRepository containerRepository;
    @Mock private BlockExecutor blockExecutor;
    @Mock private RunLiveLogService liveLog;

    private BlockStepRunner runner;
    private WorkflowRun run;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        runner = new BlockStepRunner(stepResultRepository, broadcaster, new BlockOutputValidator(),
                activityLogService, containerRepository, liveLog);
        run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING)
                .startedAt(Instant.now()).steps(new ArrayList<>()).build();
        context = ExecutionContext.builder().workflowRunId("run-1").build();
    }

    private static BlockDefinition block(String name, Map<String, Object> validation) {
        Map<String, Object> config = new HashMap<>();
        if (validation != null) config.put("validation", validation);
        return BlockDefinition.builder().type("simple").name(name).config(config).build();
    }

    private static StepResult result(StepStatus status, String output) {
        return StepResult.builder().status(status).input("the input").output(output)
                .completedAt(Instant.parse("2026-09-12T10:00:00Z")).durationMs(42L)
                .metadata(new HashMap<>(Map.of("tokens", 7))).build();
    }

    // ── startStep ──────────────────────────────────────────────────────────

    @Test
    void startStep_persistsRunningPlaceholderAndBroadcasts() {
        // step index 3 at block position 1: a wrapper block before it drew extra indices
        StepResult step = runner.startStep(run, 3, block(null, null), 1);

        assertThat(step.getStepIndex()).isEqualTo(3);
        assertThat(step.getStatus()).isEqualTo(StepStatus.RUNNING);
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getMetadata()).isEqualTo(Map.of("blockType", "simple", "blockIndex", 1));
        assertThat(step.getWorkflowRun()).isSameAs(run);
        // the step is part of the run before it is persisted, and it is saved before the broadcast
        assertThat(run.getSteps()).containsExactly(step);
        var order = inOrder(stepResultRepository, broadcaster);
        order.verify(stepResultRepository).save(step);
        order.verify(broadcaster).broadcastStepResult("run-1", step);
    }

    // ── runStep: validation ────────────────────────────────────────────────

    @Test
    void runStep_validationFailsWithoutRetry_marksFailedAndAppendsReason() {
        BlockDefinition block = block("b", Map.of("contains", "OK"));
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "nope"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("nope\n[VALIDATION] output missing required substring: OK");
        verify(blockExecutor).execute(eq(block), any());
        verifyNoInteractions(activityLogService);
    }

    @Test
    void runStep_validationFailsThenPasses_retriesAndLogsActivity() {
        BlockDefinition block = block("b", Map.of("contains", "OK", "retry_on_fail", true, "max_retries", 2));
        when(blockExecutor.execute(eq(block), any()))
                .thenReturn(result(StepStatus.COMPLETED, "nope"))
                .thenReturn(result(StepStatus.COMPLETED, "all OK"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getOutput()).isEqualTo("all OK");
        verify(activityLogService).warn(LogCategory.EXECUTION,
                "Block 0 validation failed (attempt 1/2): output missing required substring: OK",
                "WorkflowExecutor", "run-1");
    }

    @Test
    void runStep_retriesExhausted_finalCheckMarksFailed() {
        BlockDefinition block = block("b", Map.of("contains", "OK", "retry_on_fail", true, "max_retries", 1));
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "nope"));
        StepResult step = runner.startStep(run, 2, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput())
                .isEqualTo("nope\n[VALIDATION] failed after 1 retries: output missing required substring: OK");
        verify(activityLogService).warn(LogCategory.EXECUTION,
                "Block 2 validation failed (attempt 1/1): output missing required substring: OK",
                "WorkflowExecutor", "run-1");
    }

    // ── runStep: result copying ────────────────────────────────────────────

    @Test
    void runStep_copiesOutputIntoContext() {
        BlockDefinition block = block("Report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "the output"));
        StepResult step = runner.startStep(run, 1, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getInput()).isEqualTo("the input");
        assertThat(step.getOutput()).isEqualTo("the output");
        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getDurationMs()).isEqualTo(42L);
        assertThat(step.getCompletedAt()).isEqualTo(Instant.parse("2026-09-12T10:00:00Z"));
        assertThat(step.getMetadata())
                .containsEntry("tokens", 7)
                .containsEntry("blockType", "simple")
                .containsEntry("blockName", "Report");
        assertThat(context.getPreviousOutput()).isEqualTo("the output");
        assertThat(context.getVariable("step_1_output")).isEqualTo("the output");
        assertThat(context.getBlockOutput("Report")).isEqualTo("the output");
    }

    @Test
    void runStep_keepsTheBlockPositionOverExecutorMetadata() {
        BlockDefinition block = block("messen", null);
        StepResult executed = result(StepStatus.COMPLETED, "ok");
        executed.getMetadata().put("blockIndex", 99); // an executor must not move the step to another block
        when(blockExecutor.execute(eq(block), any())).thenReturn(executed);
        StepResult step = runner.startStep(run, 12, block, 2);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStepIndex()).isEqualTo(12);
        assertThat(step.getMetadata()).containsEntry("blockIndex", 2).containsEntry("tokens", 7);
    }

    @Test
    void runStep_unnamedBlock_setsNoBlockOutputAndNoBlockName() {
        BlockDefinition block = block("   ", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, null));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getMetadata()).doesNotContainKey("blockName");
        assertThat(context.getBlockOutput("   ")).isNull();
        assertThat(context.getVariable("step_0_output")).isEqualTo("");
    }

    @Test
    void runStep_completedStep_setsContextDelta() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        context.setVariable("user_var", "kept");
        run.setInputContext(new HashMap<>(Map.of("from_input", "ignored")));
        context.setVariable("from_input", "ignored");
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta()).containsExactly(Map.entry("user_var", "kept"));
    }

    @Test
    void runStep_failedStep_setsNoContextDelta() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.FAILED, "boom"));
        context.setVariable("user_var", "kept");
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta()).isNull();
    }

    // ── runStep: container activity + persist order ────────────────────────

    @Test
    void runStep_broadcastsBeforePersisting() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, "container-1");

        var order = inOrder(containerRepository, broadcaster, stepResultRepository);
        order.verify(containerRepository).touchActivity(eq("container-1"), any(Instant.class));
        order.verify(broadcaster).broadcastStepResult("run-1", step);
        order.verify(stepResultRepository).save(step);
    }

    @Test
    void runStep_noContainer_skipsActivity() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        verifyNoInteractions(containerRepository);
        // once for the RUNNING placeholder, once for the finished step
        verify(broadcaster, times(2)).broadcastStepResult("run-1", step);
    }

    @Test
    void updateContainerActivity_repositoryThrows_isSwallowed() {
        when(containerRepository.touchActivity(eq("container-1"), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        runner.updateContainerActivity("container-1");

        verify(containerRepository).touchActivity(eq("container-1"), any(Instant.class));
        verify(broadcaster, never()).broadcastStepResult(any(), any());
    }

    @Test
    void startStep_appendsToExistingSteps() {
        run.setSteps(new ArrayList<>(List.of(StepResult.builder().stepIndex(0).build())));

        StepResult step = runner.startStep(run, 1, block("b", null), 0);

        assertThat(run.getSteps()).hasSize(2);
        assertThat(run.getSteps().get(1)).isSameAs(step);
    }

    // ── runStep: taint propagation (webhook-triggered runs) ────────────────

    @Test
    void runStep_taintedRun_marksOutputAndStepVariableUntrusted() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "x; id"));
        context.setUntrustedVariable("branch", "main");
        StepResult step = runner.startStep(run, 1, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(context.isUntrusted("step_1_output")).isTrue();
        assertThat(context.resolveShellTemplate(
                "{{ previous_output }} {{ block.report.output }} {{ step_1_output }}", false))
                .isEqualTo("'x; id' 'x; id' 'x; id'");
    }

    @Test
    void runStep_taintedRun_contextDeltaCarriesTheTaint() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "x; id"));
        context.setUntrustedVariable("branch", "main; id");
        run.setInputContext(new HashMap<>(Map.of("branch", "main; id")));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta())
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch", "step_0_output"))
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE, List.of("previous_output", "report"));
    }

    @Test
    void runStep_cleanRun_addsNoTaintToContextOrDelta() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "a | b"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ step_0_output }}", false))
                .isEqualTo("a | b a | b");
        assertThat(step.getContextDelta()).isNull();
    }

    // ── runStep: live output ───────────────────────────────────────────────

    @Test
    void startStep_placeholderCarriesTheBlockName() {
        StepResult step = runner.startStep(run, 4, block("Build", null), 0);

        assertThat(step.getMetadata()).isEqualTo(Map.of("blockType", "simple", "blockName", "Build", "blockIndex", 0));
    }

    @Test
    void runStep_bindsTheStepSinkWhileExecuting_includingValidationRetries() {
        List<String> lines = new ArrayList<>();
        LiveOutputSink sink = (kind, text) -> lines.add(kind + ":" + text);
        when(liveLog.sinkFor("run-1", 3, "b")).thenReturn(sink);
        BlockDefinition block = block("b", Map.of("contains", "OK", "retry_on_fail", true, "max_retries", 1));
        List<LiveOutputSink> boundDuringExecute = new ArrayList<>();
        when(blockExecutor.execute(eq(block), any())).thenAnswer(inv -> {
            boundDuringExecute.add(LiveOutput.current());
            LiveOutput.current().emit(LiveLineKind.LINE, "attempt " + boundDuringExecute.size());
            return result(StepStatus.COMPLETED, boundDuringExecute.size() == 1 ? "nope" : "OK");
        });
        StepResult step = runner.startStep(run, 3, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(boundDuringExecute).containsExactly(sink, sink);
        assertThat(lines).containsExactly("LINE:attempt 1", "LINE:attempt 2");
        assertThat(LiveOutput.current()).isSameAs(LiveOutputSink.NOOP);
    }

    @Test
    void runStep_flushesLiveLinesBeforeTheFinalStepBroadcast() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        var order = inOrder(blockExecutor, liveLog, broadcaster, stepResultRepository);
        order.verify(blockExecutor).execute(eq(block), any());
        order.verify(liveLog).flush("run-1");
        order.verify(broadcaster).broadcastStepResult("run-1", step);
        order.verify(stepResultRepository).save(step);
    }

    @Test
    void runStep_executorThrows_unbindsAndStillFlushes() {
        when(liveLog.sinkFor("run-1", 0, "b")).thenReturn((kind, text) -> {});
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenThrow(new IllegalStateException("executor crashed"));
        StepResult step = runner.startStep(run, 0, block, 0);

        assertThatThrownBy(() -> runner.runStep(step, block, blockExecutor, context, run, null))
                .hasMessage("executor crashed");

        assertThat(LiveOutput.current()).isSameAs(LiveOutputSink.NOOP);
        verify(liveLog).flush("run-1");
    }

    // ── runStep: source index ──────────────────────────────────────────────

    @Test
    void runStep_recordsFinishedStepInSourceIndex() {
        BlockDefinition block = block("backend_tests", null);
        StepResult executed = result(StepStatus.FAILED, "boom");
        executed.getMetadata().put("exitCode", 2);
        when(blockExecutor.execute(eq(block), any())).thenReturn(executed);
        StepResult step = runner.startStep(run, 0, block, 0);

        runner.runStep(step, block, blockExecutor, context, run, null);

        var source = context.getSourceSteps().find("backend_tests").orElseThrow();
        assertThat(source.status()).isEqualTo(StepStatus.FAILED);
        assertThat(source.exitCode()).isEqualTo(2);
        assertThat(source.blockType()).isEqualTo("simple");
    }

    // ── runStep: name collisions ───────────────────────────────────────────

    /**
     * Block names are meant to be unique per workflow. When a switch case block carries the same
     * name as the switch itself, the last writer wins: the case block records its output during
     * execution, the runner overwrites it with the block's own (aggregated) output afterwards.
     * The other direction — a case block overwriting an earlier top-level block — is pinned in
     * {@code SwitchBlockExecutorTest} (12a3b244).
     */
    @Test
    void runStep_switchAggregateOverwritesAnEquallyNamedCaseBlock() {
        context.setProjectPath("/test/project");
        CommandExecutorService commandExecutor = mock(CommandExecutorService.class);
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                        .output("aus dem Kind").build())
                .thenReturn(StepResult.builder().stepIndex(2).status(StepStatus.COMPLETED)
                        .output("und noch was").build());
        Map<String, Object> config = new HashMap<>(Map.of(
                "expression", "A",
                "cases", List.of(Map.of("value", "A", "blocks", List.of(
                        Map.of("type", "simple", "name", "router", "config", Map.of("command", "tu was")),
                        Map.of("type", "simple", "config", Map.of("command", "und weiter")))))));
        BlockDefinition switchBlock = BlockDefinition.builder()
                .type("switch").name("router").config(config).build();
        StepResult step = runner.startStep(run, 0, switchBlock, 0);

        runner.runStep(step, switchBlock, new SwitchBlockExecutor(commandExecutor), context, run, null);

        // während der Ausführung stand "aus dem Kind" unter dem Namen; danach gewinnt das Aggregat
        assertThat(step.getOutput()).isEqualTo("aus dem Kind\nund noch was");
        assertThat(context.getBlockOutput("router")).isEqualTo("aus dem Kind\nund noch was");
    }

    /**
     * Seit 2718c20e trägt {@code SwitchBlockExecutor} seine benannten Case-Schritte selbst in den
     * Quellen-Index ein — und setzt damit auch dessen {@code last()}. Dass die Komprimierungs-Quelle
     * {@code "previous"} danach trotzdem den <em>switch</em> meint und nicht den letzten Case-Schritt,
     * hängt allein an der Reihenfolge in {@link BlockStepRunner#runStep}: der eigene Schritt des
     * Blocks wird NACH dessen Ausführung eingetragen und überschreibt den Case-Eintrag. Ein Umbau,
     * der das Eintragen vorzieht, bräche die Semantik von {@code "previous"} still — deshalb hier
     * festgehalten.
     */
    @Test
    void runStep_previousQuelleMeintDenSwitch_nichtDenLetztenCaseSchritt() {
        context.setProjectPath("/test/project");
        CommandExecutorService commandExecutor = mock(CommandExecutorService.class);
        when(commandExecutor.executeStepOnHost(any()))
                .thenReturn(StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                        .output("aus dem Kind").build());
        Map<String, Object> config = new HashMap<>(Map.of(
                "expression", "A",
                "cases", List.of(Map.of("value", "A", "blocks", List.of(
                        Map.of("type", "simple", "name", "befund", "config", Map.of("command", "tu was")))))));
        BlockDefinition switchBlock = BlockDefinition.builder()
                .type("switch").name("verteiler").config(config).build();
        StepResult step = runner.startStep(run, 0, switchBlock, 0);

        runner.runStep(step, switchBlock, new SwitchBlockExecutor(commandExecutor), context, run, null);

        // Der Case-Schritt ist unter seinem Namen ansprechbar …
        assertThat(context.getSourceSteps().find("befund")).isPresent();
        // … "previous" meint aber den switch: sein Schritt wurde zuletzt eingetragen.
        CompressionSources.Resolved previous = CompressionSources.resolve(context, "previous");
        assertThat(previous.origin().blockName()).isEqualTo("verteiler");
        assertThat(previous.label()).isEqualTo("verteiler");
    }
}
