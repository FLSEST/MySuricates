package com.automation.service.workflow;

import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeContextTest {

    private static StepResult step(int index, String output, Map<String, Object> delta) {
        return StepResult.builder().stepIndex(index).output(output).contextDelta(delta).build();
    }

    @Test
    void should_startEmpty_when_noInputContextAndNoSteps() {
        ResumeContext context = ResumeContext.rebuild(null, List.of());

        assertThat(context.variables()).isEmpty();
        assertThat(context.previousOutput()).isEmpty();
    }

    @Test
    void should_keepTheInputContext_andAddEachStepOutput() {
        ResumeContext context = ResumeContext.rebuild(Map.of("branch", "main"),
                List.of(step(0, "first", null), step(2, "third", null)));

        assertThat(context.variables()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "branch", "main", "step_0_output", "first", "step_2_output", "third"));
        assertThat(context.previousOutput()).isEqualTo("third");
    }

    @Test
    void should_writeAMissingOutputAsEmptyText() {
        ResumeContext context = ResumeContext.rebuild(Map.of(), List.of(step(0, "x", null), step(1, null, null)));

        assertThat(context.variables()).containsEntry("step_1_output", "");
        assertThat(context.previousOutput()).isEmpty();
    }

    @Test
    void should_applyContextDeltasInStepOrder_overTheInputContext() {
        ResumeContext context = ResumeContext.rebuild(Map.of("version", "1", "keep", "k"), List.of(
                step(0, "a", Map.of("version", "2", "added", "yes")),
                step(1, "b", Map.of("version", "3"))));

        assertThat(context.variables())
                .containsEntry("version", "3")
                .containsEntry("added", "yes")
                .containsEntry("keep", "k");
    }

    @Test
    void should_handOutAnImmutableMap() {
        ResumeContext context = ResumeContext.rebuild(Map.of("a", "b"), List.of());

        assertThatThrownBy(() -> context.variables().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
