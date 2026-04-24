package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticPhaseExceptionTest {

    @Test
    void should_storePhaseAndMessage_when_constructedWithPhaseAndMessage() {
        var ex = new AgenticPhaseException(AgenticPhaseException.Phase.REASON, "reason failed");

        assertThat(ex.getPhase()).isEqualTo(AgenticPhaseException.Phase.REASON);
        assertThat(ex.getMessage()).isEqualTo("reason failed");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(WorkflowExecutionException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_storePhaseMessageAndCause_when_constructedWithAllArguments() {
        var cause = new IllegalStateException("root cause");
        var ex = new AgenticPhaseException(AgenticPhaseException.Phase.ACT, "act failed", cause);

        assertThat(ex.getPhase()).isEqualTo(AgenticPhaseException.Phase.ACT);
        assertThat(ex.getMessage()).isEqualTo("act failed");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void should_storeApprovalPhase_when_constructedWithApprovalPhase() {
        var ex = new AgenticPhaseException(AgenticPhaseException.Phase.APPROVAL, "approval rejected");

        assertThat(ex.getPhase()).isEqualTo(AgenticPhaseException.Phase.APPROVAL);
        assertThat(ex.getMessage()).isEqualTo("approval rejected");
    }

    @Test
    void should_exposeAllThreePhases_when_enumValuesAreListed() {
        assertThat(AgenticPhaseException.Phase.values())
            .containsExactly(
                AgenticPhaseException.Phase.REASON,
                AgenticPhaseException.Phase.ACT,
                AgenticPhaseException.Phase.APPROVAL
            );
    }

    @Test
    void should_preserveNullCause_when_constructedWithNullCause() {
        var ex = new AgenticPhaseException(AgenticPhaseException.Phase.REASON, "msg", null);

        assertThat(ex.getPhase()).isEqualTo(AgenticPhaseException.Phase.REASON);
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isNull();
    }
}
