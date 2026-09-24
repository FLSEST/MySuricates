package com.automation.executor;

import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StepAiMetadataTest {

    private static Map<String, Object> hostMetadata() {
        return Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE",
                "requestedModel", "opus",
                "model", "claude-opus-4-6",
                "inputTokens", 12
        );
    }

    @Test
    void put_onImmutableMetadata_replacesWithMutableCopy() {
        StepResult step = new StepResult();
        step.setMetadata(Map.of("exitCode", 0));

        StepAiMetadata.put(step, "attempts", 3);

        assertThat(step.getMetadata()).containsEntry("exitCode", 0).containsEntry("attempts", 3);
        assertThatCode(() -> step.getMetadata().put("x", 1)).doesNotThrowAnyException();
    }

    @Test
    void put_onNullMetadata_createsMap() {
        StepResult step = new StepResult();
        step.setMetadata(null);

        StepAiMetadata.put(step, "conditionMet", true);

        assertThat(step.getMetadata()).containsExactly(Map.entry("conditionMet", true));
    }

    @Test
    void carryAi_copiesAiKeysOnly() {
        StepResult wrapper = new StepResult();

        boolean copied = StepAiMetadata.carryAi(wrapper, hostMetadata());

        assertThat(copied).isTrue();
        assertThat(wrapper.getMetadata())
                .containsEntry("aiRequest", Map.of("provider", "CLAUDE", "model", "opus"))
                .containsEntry("provider", "CLAUDE")
                .containsEntry("requestedModel", "opus")
                .containsEntry("model", "claude-opus-4-6")
                .doesNotContainKey("inputTokens");
    }

    @Test
    void carryAi_firstSourceWins() {
        StepResult wrapper = new StepResult();
        StepAiMetadata.carryAi(wrapper, hostMetadata());

        boolean copied = StepAiMetadata.carryAi(wrapper, Map.of(
                "aiRequest", Map.of("provider", "PI"), "provider", "PI"));

        assertThat(copied).isFalse();
        assertThat(wrapper.getMetadata()).containsEntry("provider", "CLAUDE");
    }

    @Test
    void carryAi_ignoresSourcesWithoutAiRequest() {
        StepResult wrapper = new StepResult();
        StepResult shellStep = new StepResult();
        shellStep.setMetadata(Map.of("exitCode", 0, "executionMode", "shell"));

        assertThat(StepAiMetadata.carryAi(wrapper, shellStep)).isFalse();
        assertThat(StepAiMetadata.carryAi(wrapper, (StepResult) null)).isFalse();
        assertThat(StepAiMetadata.carryAi(wrapper, (Map<String, Object>) null)).isFalse();
        assertThat(wrapper.getMetadata()).doesNotContainKey("aiRequest");
    }

    @Test
    void aiRequest_withoutRequest_omitsBlankValues() {
        assertThat(StepAiMetadata.aiRequest("CODEX", " ", null))
                .containsExactly(Map.entry("provider", "CODEX"));
    }
}
