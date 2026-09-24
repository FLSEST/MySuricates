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

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "CLAUDE");

        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "opus")
                .containsEntry("effort", "high")
                .containsEntry("timeoutSeconds", 120)
                .containsEntry("allowedTools", "Read,Grep")
                .doesNotContainKeys("priority", "disallowedTools");
    }

    @Test
    void aiRequest_noTools_omitsTheEmptyAllowedToolsEntry() {
        HostExecutionRequest request = HostExecutionRequest.builder()
                .projectPath("/p")
                .command("task")
                .allowedTools(AiProvider.NO_TOOLS)
                .build();

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "CLAUDE");

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

        Map<String, Object> aiRequest = HostRequestAiMetadata.aiRequest(request, "PI");

        assertThat(aiRequest)
                .containsEntry("provider", "PI")
                .containsEntry("priority", true)
                .containsEntry("disallowedTools", "Bash,Edit")
                .doesNotContainKeys("model", "effort", "timeoutSeconds", "allowedTools");
    }
}
