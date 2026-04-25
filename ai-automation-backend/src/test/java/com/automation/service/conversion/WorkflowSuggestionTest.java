package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSuggestionTest {

    @Test
    void constructor_allFields_storesAndReturnsAllValues() {
        BlockDefinition block = BlockDefinition.builder()
                .type("step-1")
                .build();
        List<BlockDefinition> blocks = List.of(block);

        WorkflowSuggestion suggestion = new WorkflowSuggestion(
                "Daily Report",
                "Generates a daily summary",
                blocks,
                "Summarize my day",
                3,
                true
        );

        assertThat(suggestion.suggestedName()).isEqualTo("Daily Report");
        assertThat(suggestion.suggestedDescription()).isEqualTo("Generates a daily summary");
        assertThat(suggestion.blocks()).containsExactly(block);
        assertThat(suggestion.originalGoal()).isEqualTo("Summarize my day");
        assertThat(suggestion.agenticStepsAnalyzed()).isEqualTo(3);
        assertThat(suggestion.available()).isTrue();
    }

    @Test
    void notAvailable_returnsUnavailableSuggestionWithDefaults() {
        WorkflowSuggestion suggestion = WorkflowSuggestion.notAvailable();

        assertThat(suggestion.available()).isFalse();
        assertThat(suggestion.suggestedName()).isNull();
        assertThat(suggestion.suggestedDescription()).isNull();
        assertThat(suggestion.originalGoal()).isNull();
        assertThat(suggestion.agenticStepsAnalyzed()).isZero();
        assertThat(suggestion.blocks()).isNotNull().isEmpty();
    }

    @Test
    void notAvailable_returnsImmutableEmptyBlocksList() {
        WorkflowSuggestion suggestion = WorkflowSuggestion.notAvailable();

        assertThat(suggestion.blocks()).isEmpty();
    }

    @Test
    void recordEquality_equalInstances_areEqualAndShareHashCode() {
        WorkflowSuggestion a = new WorkflowSuggestion(
                "name", "desc", List.of(), "goal", 2, true
        );
        WorkflowSuggestion b = new WorkflowSuggestion(
                "name", "desc", List.of(), "goal", 2, true
        );

        assertThat(a)
                .isEqualTo(b)
                .hasSameHashCodeAs(b);
    }

    @Test
    void recordEquality_differentField_isNotEqual() {
        WorkflowSuggestion a = new WorkflowSuggestion(
                "name", "desc", List.of(), "goal", 2, true
        );
        WorkflowSuggestion b = new WorkflowSuggestion(
                "name", "desc", List.of(), "goal", 2, false
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void recordEquality_notAvailableInstances_areEqual() {
        assertThat(WorkflowSuggestion.notAvailable())
                .isEqualTo(WorkflowSuggestion.notAvailable());
    }

    @Test
    void toString_containsFieldValues() {
        WorkflowSuggestion suggestion = new WorkflowSuggestion(
                "MyName", "MyDesc", List.of(), "MyGoal", 5, true
        );

        assertThat(suggestion.toString())
                .contains("MyName")
                .contains("MyDesc")
                .contains("MyGoal")
                .contains("5")
                .contains("true");
    }
}
