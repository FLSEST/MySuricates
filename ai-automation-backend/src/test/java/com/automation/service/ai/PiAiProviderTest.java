package com.automation.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PiAiProviderTest {

    @Mock private HostPiService hostPiService;

    private PiAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PiAiProvider(hostPiService);
    }

    @Test
    void getId_returnsPI() {
        assertThat(provider.getId()).isEqualTo("PI");
    }

    @Test
    void getCapabilities_systemPromptAndModelAndToolsAreSupported() {
        AiProviderCapabilities caps = provider.getCapabilities();

        assertThat(caps.supportsSystemPrompt()).isTrue();
        assertThat(caps.supportsModelSelection()).isTrue();
        assertThat(caps.supportsAllowedTools()).isTrue();
        assertThat(caps.supportsMultiTurnReasoning()).isTrue();
        assertThat(caps.supportsMaxTurns()).isTrue();
        // Token tracking is currently estimated — flagged as false until JSONL parser lands.
        assertThat(caps.tokenTracking()).isFalse();
        assertThat(caps.supportsStreamJson()).isFalse();
    }

    @Test
    void reason_delegatesToHostService() throws IOException {
        when(hostPiService.reason(eq("prompt"), eq("system"), isNull(), eq("/path"), eq(3), eq(60), isNull()))
                .thenReturn(new HostPiService.ReasoningResult("pi reasoning output"));

        var request = new AiProvider.ReasoningRequest("prompt", "system", "/path", 3, 60);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("pi reasoning output");
        // Estimated metadata is flagged
        assertThat(result.metadata().estimated()).isTrue();
    }

    @Test
    void reason_forwardsWorkflowModelToHostService() throws IOException {
        when(hostPiService.reason(eq("prompt"), eq("system"), eq("openai-codex/gpt-5.5"), eq("/path"), eq(3), eq(60), isNull()))
                .thenReturn(new HostPiService.ReasoningResult("out"));

        var request = new AiProvider.ReasoningRequest("prompt", "system", "openai-codex/gpt-5.5", "/path", 3, 60, null);
        provider.reason(request);

        verify(hostPiService).reason(eq("prompt"), eq("system"), eq("openai-codex/gpt-5.5"), eq("/path"), eq(3), eq(60), isNull());
    }

    @Test
    void reason_forwardsEffortToHostService() throws IOException {
        when(hostPiService.reason(eq("prompt"), isNull(), isNull(), isNull(), isNull(), isNull(), eq("xhigh")))
                .thenReturn(new HostPiService.ReasoningResult("out"));

        var request = new AiProvider.ReasoningRequest("prompt", null, null, null, null, "xhigh");
        provider.reason(request);

        verify(hostPiService).reason(eq("prompt"), isNull(), isNull(), isNull(), isNull(), isNull(), eq("xhigh"));
    }

    @Test
    void executeAction_delegatesAllFieldsToHostService() throws IOException {
        when(hostPiService.executeAction(
                eq("user prompt"),
                eq("google/gemini-2.0-flash"),
                eq("instructions"),
                eq("/work"),
                eq(120),
                eq(true),
                eq(List.of("read", "bash")),
                isNull(),
                eq("high")))
                .thenReturn(new HostPiService.ActionResult("done", 0));

        var request = new AiProvider.ActionRequest(
                "user prompt", "google/gemini-2.0-flash", "instructions", "/work",
                120, true, false, List.of("read", "bash"), null, "high");
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("done");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.metadata().estimated()).isTrue();
        verify(hostPiService).executeAction(
                eq("user prompt"),
                eq("google/gemini-2.0-flash"),
                eq("instructions"),
                eq("/work"),
                eq(120),
                eq(true),
                eq(List.of("read", "bash")),
                isNull(),
                eq("high"));
    }

    @Test
    void executeAction_nonZeroExit_isPropagated() throws IOException {
        when(hostPiService.executeAction(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(new HostPiService.ActionResult("error message", 2));

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, true, null, null);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.text()).isEqualTo("error message");
    }

    @Test
    void buildConversationPrompt_emptyHistory_omitsHistoryHeader() {
        String result = provider.buildConversationPrompt("system", List.of());

        assertThat(result).doesNotContain("CONVERSATION HISTORY");
        assertThat(result).contains("=== YOUR RESPONSE ===");
    }

    @Test
    void buildConversationPrompt_withHistory_formatsTurnsCorrectly() {
        var history = List.of(
                new HostClaudeService.Turn("user", "Hello"),
                new HostClaudeService.Turn("assistant", "Hi there"));

        String result = provider.buildConversationPrompt("system", history);

        assertThat(result).contains("=== CONVERSATION HISTORY ===");
        assertThat(result).contains("[USER]");
        assertThat(result).contains("Hello");
        assertThat(result).contains("[ASSISTANT]");
        assertThat(result).contains("Hi there");
        assertThat(result).contains("=== YOUR RESPONSE ===");
    }

    @Test
    void runContextFilesReachPiThroughTheSystemPrompt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) throws IOException {
        java.nio.file.Path skills = project.resolve(RunContextFiles.SKILLS_CONTEXT);
        java.nio.file.Files.createDirectories(skills.getParent());
        java.nio.file.Files.writeString(skills, "## Skills\nreview carefully");
        java.nio.file.Files.writeString(project.resolve(RunContextFiles.WORKFLOW_MEMORY), "## Memory\nuse mvnw");
        String dir = project.toString();
        String expectedSystem = "instructions\n\n## Skills\nreview carefully\n\n## Memory\nuse mvnw";
        when(hostPiService.executeAction(eq("do it"), isNull(), eq(expectedSystem), eq(dir), isNull(), anyBoolean(), isNull(), isNull(), isNull()))
                .thenReturn(new HostPiService.ActionResult("ok", 0));
        when(hostPiService.reason(eq("think"), eq(expectedSystem), isNull(), eq(dir), isNull(), isNull(), isNull()))
                .thenReturn(new HostPiService.ReasoningResult("thought"));

        provider.executeAction(new AiProvider.ActionRequest("do it", null, "instructions", dir));
        provider.reason(new AiProvider.ReasoningRequest("think", "instructions", null, dir, null, null, null));

        verify(hostPiService).executeAction(eq("do it"), isNull(), eq(expectedSystem), eq(dir), isNull(), anyBoolean(), isNull(), isNull(), isNull());
        verify(hostPiService).reason(eq("think"), eq(expectedSystem), isNull(), eq(dir), isNull(), isNull(), isNull());
    }
}
