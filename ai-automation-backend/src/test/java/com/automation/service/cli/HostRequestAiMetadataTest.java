package com.automation.service.cli;

import com.automation.service.ai.AiProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Moved from {@code StepAiMetadataTest} together with the request overload of {@code aiRequest}. */
class HostRequestAiMetadataTest {

    @Test
    void aiRequest_fromRequest_matchesHostStepShape() {
        HostExecutionRequest request = HostExecutionRequest.builder()
                .projectPath("/p")
                .command("task")
                .model("opus")
                .effort("high")
                .timeoutSeconds(120)
                .allowedTools(List.of("Read", "Grep"))
                .build();

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "CLAUDE", true);

        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "opus")
                .containsEntry("effort", "high")
                .containsEntry("timeoutSeconds", 120)
                .containsEntry("allowedTools", "Read,Grep")
                .doesNotContainKeys("priority", "disallowedTools");
    }

    /** Codex has no effort flag and ignores the setting, so the step does not claim one (TODO 20adda30). */
    @Test
    void aiRequest_providerWithoutEffort_omitsTheEffort() {
        HostExecutionRequest request = HostExecutionRequest.builder()
                .projectPath("/p")
                .command("task")
                .model("gpt-5.5")
                .effort("high")
                .build();

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "CODEX", false);

        assertThat(aiRequest)
                .containsEntry("provider", "CODEX")
                .containsEntry("model", "gpt-5.5")
                .doesNotContainKey("effort");
    }

    @Test
    void aiRequest_noTools_omitsTheEmptyAllowedToolsEntry() {
        HostExecutionRequest request = HostExecutionRequest.builder()
                .projectPath("/p")
                .command("task")
                .allowedTools(AiProvider.NO_TOOLS)
                .build();

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "CLAUDE", true);

        assertThat(aiRequest).containsExactly(Map.entry("provider", "CLAUDE"));
    }

    @Test
    void aiRequest_interactiveCall_carriesPriorityAndDisallowedTools() {
        HostExecutionRequest request = HostExecutionRequest.builder()
                .projectPath("/p")
                .command("chat")
                .priority(true)
                .disallowedTools(List.of("Bash", " ", "Edit"))
                .build();

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "PI", true);

        assertThat(aiRequest)
                .containsEntry("provider", "PI")
                .containsEntry("priority", true)
                .containsEntry("disallowedTools", "Bash,Edit")
                .doesNotContainKeys("model", "effort", "timeoutSeconds", "allowedTools");
    }
}
