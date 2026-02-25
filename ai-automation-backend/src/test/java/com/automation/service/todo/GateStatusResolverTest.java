package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatusResolverTest {

    private GateStatusResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GateStatusResolver();
    }

    @Test
    void resolve_explicitRed_returnsRed() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "RED", "gate_category", "lint-failure", "gate_description", "3 errors"));

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.RED);
        assertThat(result.category()).isEqualTo("lint-failure");
        assertThat(result.description()).isEqualTo("3 errors");
    }

    @Test
    void resolve_explicitYellow_returnsYellow() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "YELLOW", "gate_category", "test-warning"));

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.YELLOW);
        assertThat(result.category()).isEqualTo("test-warning");
    }

    @Test
    void resolve_explicitGreen_returnsGreen() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "GREEN"));

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.GREEN);
        assertThat(result.category()).isEqualTo("success");
    }

    @Test
    void resolve_explicitCaseInsensitive_works() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "yellow"));

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.YELLOW);
    }

    @Test
    void resolve_failedStatus_derivesRed() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, Map.of());

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.RED);
        assertThat(result.category()).isEqualTo("execution-failure");
    }

    @Test
    void resolve_completedStatus_derivesGreen() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of());

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.GREEN);
    }

    @Test
    void resolve_cancelledStatus_returnsNull() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.CANCELLED, Map.of());

        GateResult result = resolver.resolve(event);

        assertThat(result).isNull();
    }

    @Test
    void resolve_pausedStatus_returnsNull() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.PAUSED, Map.of());

        GateResult result = resolver.resolve(event);

        assertThat(result).isNull();
    }

    @Test
    void resolve_invalidExplicitGate_fallsBackToDerivation() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED,
                Map.of("gate_status", "INVALID"));

        GateResult result = resolver.resolve(event);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GateStatus.RED);
        assertThat(result.category()).isEqualTo("execution-failure");
    }

    @Test
    void resolve_failedWithErrorMessage_includesDescription() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test", "proj-1", "/path",
                RunStatus.FAILED, Map.of(), Map.of(),
                Map.of("message", "NullPointerException"));

        GateResult result = resolver.resolve(event);

        assertThat(result.description()).isEqualTo("NullPointerException");
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status, Map<String, Object> runMetadata) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow", "proj-1", "/workspace/test",
                status, runMetadata, Map.of(), Map.of());
    }
}
