package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DialogWorkflowSuggestionTest {

    @Test
    void constructor_allFields_storesAndReturnsAllValues() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "echo hello"))
                .build();
        List<BlockDefinition> blocks = List.of(block);

        DialogWorkflowSuggestion suggestion = new DialogWorkflowSuggestion(
                "My Workflow",
                "Does something useful",
                blocks,
                "session-abc",
                "/path/to/source.jsonl",
                "/home/user/project",
                0.85,
                "scripting",
                null,
                42,
                true
        );

        assertThat(suggestion.suggestedName()).isEqualTo("My Workflow");
        assertThat(suggestion.suggestedDescription()).isEqualTo("Does something useful");
        assertThat(suggestion.blocks()).hasSize(1);
        assertThat(suggestion.blocks().get(0).getType()).isEqualTo("shell");
        assertThat(suggestion.sessionId()).isEqualTo("session-abc");
        assertThat(suggestion.sourceFilePath()).isEqualTo("/path/to/source.jsonl");
        assertThat(suggestion.projectPath()).isEqualTo("/home/user/project");
        assertThat(suggestion.confidence()).isEqualTo(0.85);
        assertThat(suggestion.patternType()).isEqualTo("scripting");
        assertThat(suggestion.notAvailableReason()).isNull();
        assertThat(suggestion.toolCallsAnalyzed()).isEqualTo(42);
        assertThat(suggestion.available()).isTrue();
    }

    @Test
    void notAvailable_setsAvailableFalseAndReason() {
        DialogWorkflowSuggestion suggestion = DialogWorkflowSuggestion.notAvailable("Session too short");

        assertThat(suggestion.available()).isFalse();
        assertThat(suggestion.notAvailableReason()).isEqualTo("Session too short");
        assertThat(suggestion.blocks()).isEmpty();
        assertThat(suggestion.confidence()).isEqualTo(0.0);
        assertThat(suggestion.toolCallsAnalyzed()).isEqualTo(0);
        assertThat(suggestion.suggestedName()).isNull();
        assertThat(suggestion.suggestedDescription()).isNull();
        assertThat(suggestion.sessionId()).isNull();
        assertThat(suggestion.sourceFilePath()).isNull();
        assertThat(suggestion.projectPath()).isNull();
        assertThat(suggestion.patternType()).isNull();
    }
}
