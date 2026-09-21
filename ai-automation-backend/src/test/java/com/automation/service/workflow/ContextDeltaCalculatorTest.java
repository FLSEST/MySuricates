package com.automation.service.workflow;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class ContextDeltaCalculatorTest {

    @Test
    void computeKeepsOnlyNewUserVariables() {
        ExecutionContext context = ExecutionContext.builder().build();
        context.setVariable("custom", "new");
        context.setVariable("from_input", "unchanged");
        context.setVariable("step_12_output", "derivable");
        context.setVariable("workflow_id", "system");

        Map<String, Object> delta = ContextDeltaCalculator.compute(
                context, Map.of("from_input", "original"));

        assertThat(delta).containsOnly(entry("custom", "new"));
    }

    @Test
    void computeReturnsNullWhenNothingNeedsPersistence() {
        ExecutionContext context = ExecutionContext.builder().build();
        context.setVariable("step_0_output", "result");
        context.setVariable("request_metrics", "facts");

        assertThat(ContextDeltaCalculator.compute(context, null)).isNull();
    }

    @Test
    void computePersistsTaintEvenWhenItsInputValueIsAlreadyStoredOnTheRun() {
        ExecutionContext context = ExecutionContext.builder().build();
        context.setUntrustedVariable("payload", "external");

        Map<String, Object> delta = ContextDeltaCalculator.compute(
                context, Map.of("payload", "external"));

        assertThat(delta)
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("payload"))
                .doesNotContainKey("payload");
    }
}
