package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The field formats an AI actor's answer is checked against (TODO dca0c03e). */
class HumanInputAnswersTest {

    private static HumanInputBlockExecutor.Settings single(String inputType, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(Map.of("prompt", "p", "input_type", inputType));
        config.putAll(extra);
        return HumanInputBlockExecutor.Settings.from(config,
                ExecutionContext.builder().containerId("c").workflowRunId("r").build());
    }

    @Test
    void number_date_checkbox_andPattern_areChecked() {
        assertThat(HumanInputAnswers.validate("12.5", single("number", Map.of())).answer()).isEqualTo("12.5");
        assertThat(HumanInputAnswers.validate("twelve", single("number", Map.of())).error()).contains("not a number");
        assertThat(HumanInputAnswers.validate("2026-10-12", single("date", Map.of())).answer()).isEqualTo("2026-10-12");
        assertThat(HumanInputAnswers.validate("12.10.2026", single("date", Map.of())).error()).contains("YYYY-MM-DD");
        assertThat(HumanInputAnswers.validate("Yes", single("checkbox", Map.of())).answer()).isEqualTo("true");
        assertThat(HumanInputAnswers.validate("maybe", single("checkbox", Map.of())).error()).contains("neither true nor false");
        var pattern = single("text", Map.of("validation_pattern", "^PO-\\d+$"));
        assertThat(HumanInputAnswers.validate("PO-7", pattern).answer()).isEqualTo("PO-7");
        assertThat(HumanInputAnswers.validate("7", pattern).error()).contains("does not match the pattern");
    }

    @Test
    void required_blankAnswer_isRejected_optionalIsKeptEmpty() {
        assertThat(HumanInputAnswers.validate("  ", single("text", Map.of())).error()).contains("a value is required");
        assertThat(HumanInputAnswers.validate("", single("text", Map.of("required", false))).answer()).isEmpty();
    }

    @Test
    void task_describesTheSingleField() {
        String task = HumanInputAnswers.task("Wie viele?", single("number", Map.of("required", false)));

        assertThat(task).contains("## Input requested", "Wie viele?", "One value (a number; optional, empty allowed)");
    }

    @Test
    void same_comparesSelectIgnoringCase_andTextExactly() {
        assertThat(HumanInputAnswers.same("English", "english", single("select", Map.of("options", List.of("English"))))).isTrue();
        assertThat(HumanInputAnswers.same("Alice", "alice", single("text", Map.of()))).isFalse();
        assertThat(HumanInputAnswers.same(" Alice ", "Alice", single("text", Map.of()))).isTrue();
    }

    @Test
    void same_normalizesTheProposalFirst() {
        assertThat(HumanInputAnswers.same("true", "yes", single("checkbox", Map.of()))).isTrue();
        assertThat(HumanInputAnswers.same("false", "Nein", single("checkbox", Map.of()))).isTrue();
        assertThat(HumanInputAnswers.same("true", "maybe", single("checkbox", Map.of())))
                .as("a proposal that fails the checks never agrees").isFalse();
    }

    @Test
    void same_formSelectIgnoresCase_likeTheSingleField() {
        Map<String, Object> config = new HashMap<>(Map.of("prompt", "p", "variable_name", "f", "fields", List.of(
                Map.of("variable_name", "size", "input_type", "select", "options", List.of("S", "M")),
                Map.of("variable_name", "done", "input_type", "checkbox"))));
        var settings = HumanInputBlockExecutor.Settings.from(config,
                ExecutionContext.builder().containerId("c").workflowRunId("r").build());

        assertThat(HumanInputAnswers.same("{\"size\":\"M\",\"done\":\"true\"}", "{\"size\":\"m\",\"done\":\"ja\"}", settings))
                .isTrue();
        assertThat(HumanInputAnswers.same("{\"size\":\"S\",\"done\":\"true\"}", "{\"size\":\"m\",\"done\":\"ja\"}", settings))
                .isFalse();
    }
}
