package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedConversionTest {

    private static StepResult agentic(String goal, Object iterations) {
        Map<String, Object> meta = new HashMap<>();
        if (goal != null) meta.put("goal", goal);
        if (iterations != null) meta.put("iterations", iterations);
        return StepResult.builder().metadata(meta).build();
    }

    private static Map<String, Object> iteration(String action, Object exitCode) {
        Map<String, Object> iter = new HashMap<>();
        iter.put("action", action);
        if (exitCode != null) iter.put("exitCode", exitCode);
        return iter;
    }

    @Test
    void should_turnEverySuccessfulActionIntoASimpleBlock() {
        WorkflowSuggestion suggestion = RuleBasedConversion.suggest(List.of(agentic("Build it", List.of(
                iteration("mvn package", 0),
                iteration("ls target", null)))));

        assertThat(suggestion.available()).isTrue();
        assertThat(suggestion.agenticStepsAnalyzed()).isEqualTo(2);
        assertThat(suggestion.originalGoal()).isEqualTo("Build it");
        assertThat(suggestion.suggestedName()).isEqualTo("Auto: Build it");
        assertThat(suggestion.suggestedDescription())
                .isEqualTo("Automatically generated from successful agentic run. Original goal: Build it");
        assertThat(suggestion.blocks()).extracting(BlockDefinition::getType).containsOnly("simple");
        assertThat(suggestion.blocks().get(0).getConfig())
                .containsExactly(Map.entry("command", "mvn package"), Map.entry("on_failure", "stop"));
        assertThat(suggestion.blocks().get(1).getConfig()).containsEntry("command", "ls target");
    }

    @Test
    void should_skipFailedEmptyAndMalformedIterations() {
        WorkflowSuggestion suggestion = RuleBasedConversion.suggest(List.of(agentic("g", List.of(
                iteration("fails", 1),
                iteration("", 0),
                iteration(null, 0),
                "not a map",
                iteration("ok", 0L),
                iteration("text exit code counts as unknown", "2")))));

        assertThat(suggestion.blocks()).extracting(b -> b.getConfig().get("command"))
                .containsExactly("ok", "text exit code counts as unknown");
    }

    @Test
    void should_skipStepsWithoutAnIterationList() {
        WorkflowSuggestion suggestion = RuleBasedConversion.suggest(List.of(
                agentic("first", null),
                agentic("second", "not a list"),
                agentic("third", List.of(iteration("echo", 0)))));

        assertThat(suggestion.blocks()).hasSize(1);
        assertThat(suggestion.originalGoal()).isEqualTo("third");
    }

    @Test
    void should_takeTheGoalOfTheLastStep_evenWhenItHadNoActions() {
        WorkflowSuggestion suggestion = RuleBasedConversion.suggest(List.of(
                agentic("first", List.of(iteration("echo", 0))),
                agentic(null, null)));

        assertThat(suggestion.originalGoal()).isEmpty();
        assertThat(suggestion.suggestedName()).isEqualTo("Auto: ");
    }

    @Test
    void should_beNotAvailable_withoutAnySuccessfulAction() {
        assertThat(RuleBasedConversion.suggest(List.of())).isEqualTo(WorkflowSuggestion.notAvailable());
        assertThat(RuleBasedConversion.suggest(List.of(agentic("g", List.of(iteration("x", 3))))).available())
                .isFalse();
    }

    @Test
    void should_truncateALongGoalInTheName_butNotInTheDescription() {
        String goal = "g".repeat(80);

        WorkflowSuggestion suggestion = RuleBasedConversion.suggest(List.of(agentic(goal, List.of(iteration("x", 0)))));

        assertThat(suggestion.suggestedName()).isEqualTo("Auto: " + "g".repeat(60) + "...");
        assertThat(suggestion.suggestedDescription()).endsWith(goal);
    }

    @Test
    void truncate_handlesNullShortAndExactLengths() {
        assertThat(RuleBasedConversion.truncate(null, 5)).isEmpty();
        assertThat(RuleBasedConversion.truncate("abc", 5)).isEqualTo("abc");
        assertThat(RuleBasedConversion.truncate("abcde", 5)).isEqualTo("abcde");
        assertThat(RuleBasedConversion.truncate("abcdef", 5)).isEqualTo("abcde...");
    }
}
