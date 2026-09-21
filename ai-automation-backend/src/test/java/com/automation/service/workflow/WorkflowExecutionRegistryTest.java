package com.automation.service.workflow;

import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowExecutionRegistryTest {

    private WorkflowExecutionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowExecutionRegistry();
    }

    private ExecutionContext buildContext(String containerId, String runId) {
        return ExecutionContext.builder()
                .containerId(containerId)
                .workflowRunId(runId)
                .build();
    }

    @Test
    void registerAndGet() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);

        assertThat(registry.get("run-1")).isSameAs(ctx);
    }

    @Test
    void get_unknownRun_returnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void unregister_removesExecution() {
        registry.register("run-1", buildContext("c1", "run-1"));
        registry.unregister("run-1");

        assertThat(registry.get("run-1")).isNull();
        assertThat(registry.getActiveRunCount()).isZero();
    }

    @Test
    void getActiveRunCount() {
        assertThat(registry.getActiveRunCount()).isZero();

        registry.register("run-1", buildContext("c1", "run-1"));
        registry.register("run-2", buildContext("c2", "run-2"));
        assertThat(registry.getActiveRunCount()).isEqualTo(2);

        registry.unregister("run-1");
        assertThat(registry.getActiveRunCount()).isEqualTo(1);
    }

    @Test
    void isRunActive() {
        registry.register("run-1", buildContext("c1", "run-1"));

        assertThat(registry.isRunActive("run-1")).isTrue();
        assertThat(registry.isRunActive("run-2")).isFalse();
    }

    @Test
    void getActiveContainerIds() {
        registry.register("run-1", buildContext("c1", "run-1"));
        registry.register("run-2", buildContext("c2", "run-2"));
        registry.register("run-3", buildContext("c1", "run-3")); // duplicate container

        Set<String> ids = registry.getActiveContainerIds();
        assertThat(ids).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    void getContainerIdForRun() {
        registry.register("run-1", buildContext("c1", "run-1"));

        assertThat(registry.getContainerIdForRun("run-1")).isEqualTo("c1");
        assertThat(registry.getContainerIdForRun("nonexistent")).isNull();
    }

    @Test
    void pauseExecution_pausesContext() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);

        assertThat(ctx.isPaused()).isFalse();
        registry.pauseExecution("run-1");
        assertThat(ctx.isPaused()).isTrue();
    }

    @Test
    void pauseExecution_unknownRun_doesNotThrow() {
        registry.pauseExecution("nonexistent"); // should just log warning
    }

    @Test
    void resumeExecution_resumesContext() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        ctx.pause();
        registry.register("run-1", ctx);

        registry.resumeExecution("run-1");
        assertThat(ctx.isPaused()).isFalse();
    }

    @Test
    void resumeExecution_unknownRun_doesNotThrow() {
        registry.resumeExecution("nonexistent");
    }

    @Test
    void resolveApproval_resolvesContext() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        var future = ctx.requestApproval(ApprovalRequest.builder()
                .question("Proceed?")
                .options(java.util.List.of("Yes", "No"))
                .build());
        registry.register("run-1", ctx);

        registry.resolveApproval("run-1", "Yes");
        assertThat(future).isCompletedWithValue("Yes");
    }

    @Test
    void resolveApproval_unknownRun_doesNotThrow() {
        registry.resolveApproval("nonexistent", "Yes");
    }

    @Test
    void getPendingApprovalCount_noApprovals() {
        registry.register("run-1", buildContext("c1", "run-1"));
        assertThat(registry.getPendingApprovalCount()).isZero();
    }

    @Test
    void getPendingApprovalCount_withApprovals() {
        ExecutionContext ctx1 = buildContext("c1", "run-1");
        ctx1.requestApproval(ApprovalRequest.builder()
                .question("Q1?").options(java.util.List.of("Yes")).build());
        registry.register("run-1", ctx1);

        ExecutionContext ctx2 = buildContext("c2", "run-2");
        registry.register("run-2", ctx2);

        assertThat(registry.getPendingApprovalCount()).isEqualTo(1);
    }

    @Test
    void getPendingApproval_returnsRequest() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        ApprovalRequest request = ApprovalRequest.builder()
                .question("Proceed?").options(java.util.List.of("Yes", "No")).build();
        ctx.requestApproval(request);
        registry.register("run-1", ctx);

        assertThat(registry.getPendingApproval("run-1")).isSameAs(request);
    }

    @Test
    void getPendingApproval_noApproval_returnsNull() {
        registry.register("run-1", buildContext("c1", "run-1"));
        assertThat(registry.getPendingApproval("run-1")).isNull();
    }

    @Test
    void getPendingApproval_unknownRun_returnsNull() {
        assertThat(registry.getPendingApproval("nonexistent")).isNull();
    }

    // ── Cancel before thread registration (TODO 45d09d19) ─────────────────────
    // register() hands back the previous context so the executor can carry a
    // cancel/pause flag over from the provisional context; cancel() flags both
    // sides of a concurrent swap.

    @Test
    void register_returnsPreviousContext() {
        ExecutionContext provisional = buildContext(null, "run-1");
        ExecutionContext real = buildContext("c1", "run-1");

        assertThat(registry.register("run-1", provisional)).isNull();
        assertThat(registry.register("run-1", real)).isSameAs(provisional);
        assertThat(registry.get("run-1")).isSameAs(real);
    }

    @Test
    void cancel_returnsFalseWhenUnknown() {
        assertThat(registry.cancel("nonexistent")).isFalse();
    }

    @Test
    void cancel_flagsContextAndReturnsTrue() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);

        assertThat(registry.cancel("run-1")).isTrue();
        assertThat(ctx.shouldStop()).isTrue();
    }

    @Test
    void registerProvisional_registersCancellablePlaceholder() {
        registry.registerProvisional("run-1");

        assertThat(registry.isRunActive("run-1")).isTrue();
        assertThat(registry.getActiveRunCount()).isEqualTo(1);
        // Controllable, but not usable for anything that needs a container or the project cwd.
        assertThat(registry.getStartedContext("run-1")).isNull();
        assertThat(registry.cancelRequested("run-1")).isFalse();

        assertThat(registry.cancel("run-1")).isTrue();
        assertThat(registry.cancelRequested("run-1")).isTrue();
    }

    @Test
    void registerProvisional_marksQueued_promoteClearsIt() {
        registry.registerProvisional("run-1");
        assertThat(registry.getStartedContext("run-1")).isNull();

        ExecutionContext real = buildContext("c1", "run-1");
        registry.promote("run-1", real);
        assertThat(registry.getStartedContext("run-1")).isSameAs(real);

        // A second run of the same id (retry after unregister) must start queued again.
        registry.unregister("run-1");
        registry.registerProvisional("run-1");
        assertThat(registry.getStartedContext("run-1")).isNull();
    }

    @Test
    void unregister_clearsQueuedState() {
        registry.registerProvisional("run-1");
        registry.unregister("run-1");

        assertThat(registry.isRunActive("run-1")).isFalse();
        // No stale queued entry: a context registered afterwards counts as started.
        ExecutionContext real = buildContext("c1", "run-1");
        registry.promote("run-1", real);
        assertThat(registry.getStartedContext("run-1")).isSameAs(real);
    }

    @Test
    void cancelRequested_unknownRun_isFalse() {
        assertThat(registry.cancelRequested("nonexistent")).isFalse();
    }

    @Test
    void promote_carriesCancelFlagToTheRealContext() {
        registry.registerProvisional("run-1");
        registry.cancel("run-1");
        ExecutionContext real = buildContext("c1", "run-1");

        registry.promote("run-1", real);

        assertThat(real.shouldStop()).isTrue();
        assertThat(registry.get("run-1")).isSameAs(real);
        assertThat(registry.getStartedContext("run-1")).isSameAs(real);
    }

    @Test
    void promote_carriesPauseFlagToTheRealContext() {
        registry.registerProvisional("run-1");
        registry.pauseExecution("run-1");
        ExecutionContext real = buildContext("c1", "run-1");

        registry.promote("run-1", real);

        assertThat(real.isPaused()).isTrue();
        assertThat(real.shouldStop()).isFalse();
    }

    @Test
    void promote_withoutProvisional_leavesTheContextUntouched() {
        ExecutionContext real = buildContext("c1", "run-1");

        registry.promote("run-1", real);

        assertThat(real.shouldStop()).isFalse();
        assertThat(real.isPaused()).isFalse();
        assertThat(registry.get("run-1")).isSameAs(real);
    }

    @Test
    void getStartedContext_unknownRun_returnsNull() {
        assertThat(registry.getStartedContext("nonexistent")).isNull();
    }

    @Test
    void cancel_flagsBothContextsWhenSwappedConcurrently() {
        ExecutionContext provisional = buildContext(null, "run-1");
        ExecutionContext real = buildContext("c1", "run-1");
        // Simulates the interleaving where the executor swaps the provisional context for the
        // real one between cancel()'s two map reads: both must end up cancelled.
        WorkflowExecutionRegistry swapping = new WorkflowExecutionRegistry() {
            private boolean swapped;

            @Override
            public ExecutionContext get(String runId) {
                ExecutionContext current = super.get(runId);
                if (!swapped) {
                    swapped = true;
                    register(runId, real);
                }
                return current;
            }
        };
        swapping.register("run-1", provisional);

        assertThat(swapping.cancel("run-1")).isTrue();
        assertThat(provisional.shouldStop()).isTrue();
        assertThat(real.shouldStop()).isTrue();
    }

    // --- TODO 50c7b5d5: ein Approval nimmt nur seine Optionen an ---

    @Test
    void resolveApproval_answerNotOffered_isRefusedAndKeepsWaiting() {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);
        var future = ctx.requestApproval(ApprovalRequest.builder()
                .blockId("approval-3").question("Satz uebernehmen?").options(List.of("Ja", "Nein")).stepIndex(3).build());

        assertThatThrownBy(() -> registry.resolveApproval("run-1", "No"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ja");
        assertThat(future).isNotDone();
        assertThat(registry.getPendingApproval("run-1")).isNotNull();
    }

    @Test
    void resolveApproval_offeredAnswerInOtherCase_passesTheOptionSpelling() throws Exception {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);
        var future = ctx.requestApproval(ApprovalRequest.builder()
                .blockId("approval-3").question("Satz uebernehmen?").options(List.of("Ja", "Nein")).stepIndex(3).build());

        registry.resolveApproval("run-1", " nein ");

        assertThat(future.get()).isEqualTo("Nein");
    }

    @Test
    void resolveApproval_humanInput_acceptsFreeText() throws Exception {
        ExecutionContext ctx = buildContext("c1", "run-1");
        registry.register("run-1", ctx);
        var future = ctx.requestApproval(ApprovalRequest.builder()
                .blockId("human-input-2").question("Name?").type("human-input")
                .options(List.of("dev", "prod")).stepIndex(2).build());

        registry.resolveApproval("run-1", "{\"env\": \"staging\"}");

        assertThat(future.get()).isEqualTo("{\"env\": \"staging\"}");
    }

    // --- TODO 52d946cc: wartende Eingaben über alle Läufe ---

    @Test
    void listPendingInputs_onlyWaitingRuns_oldestFirst() {
        ExecutionContext busy = buildContext("c1", "run-busy");
        ExecutionContext newer = buildContext("c2", "run-newer");
        ExecutionContext older = buildContext("c3", "run-older");
        registry.register("run-busy", busy);
        registry.register("run-newer", newer);
        registry.register("run-older", older);
        Instant now = Instant.now();
        newer.requestApproval(ApprovalRequest.builder().blockId("approval-1").question("B?")
                .requestedAt(now).build());
        older.requestApproval(ApprovalRequest.builder().blockId("human-input-4").question("A?").type("human-input")
                .requestedAt(now.minusSeconds(600)).build());

        var pending = registry.listPendingInputs();

        assertThat(pending).extracting(WorkflowExecutionRegistry.PendingInput::runId)
                .containsExactly("run-older", "run-newer");
        assertThat(pending.get(0).request().getQuestion()).isEqualTo("A?");
    }

    @Test
    void listPendingInputs_nothingWaiting_isEmpty() {
        registry.register("run-1", buildContext("c1", "run-1"));

        assertThat(registry.listPendingInputs()).isEmpty();
    }
}
