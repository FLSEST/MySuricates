package com.automation.service.workflow;

import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
