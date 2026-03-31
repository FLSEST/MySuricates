package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexAiProviderTest {

    @Mock private HostCodexService hostCodexService;
    @Mock private SummarizationConfig summarizationConfig;

    private CodexAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CodexAiProvider(hostCodexService, summarizationConfig);
    }

    @Test
    void getId_returnsCODEX() {
        assertThat(provider.getId()).isEqualTo("CODEX");
    }

    @Test
    void getCapabilities_returnsMinimal() {
        AiProviderCapabilities caps = provider.getCapabilities();
        assertThat(caps).isEqualTo(AiProviderCapabilities.minimal());
    }

    @Test
    void reason_delegatesToHostService() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(contains("SYSTEM INSTRUCTIONS"), eq(Path.of("/path")), eq(120)))
                .thenReturn("codex output");

        var request = new AiProvider.ReasoningRequest("prompt", "system", "/path", 3, null);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("codex output");
    }

    @Test
    void reason_usesExplicitTimeout() throws IOException {
        when(hostCodexService.execute(anyString(), any(), eq(60)))
                .thenReturn("output");

        var request = new AiProvider.ReasoningRequest("prompt", null, null, 3, 60);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("output");
    }

    @Test
    void executeAction_success_returnsExitCode0() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120)))
                .thenReturn("action output");

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("action output");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void executeAction_ioException_returnsExitCode1() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120)))
                .thenThrow(new IOException("CLI failed"));

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.text()).contains("CLI failed");
    }

    @Test
    void buildConversationPrompt_formatsCorrectly() {
        List<HostClaudeService.Turn> history = List.of(
                new HostClaudeService.Turn("user", "hello"),
                new HostClaudeService.Turn("assistant", "hi"));

        String result = provider.buildConversationPrompt("system prompt", history);

        assertThat(result).contains("=== INSTRUCTIONS ===");
        assertThat(result).contains("system prompt");
        assertThat(result).contains("[USER]");
        assertThat(result).contains("hello");
        assertThat(result).contains("[ASSISTANT]");
        assertThat(result).contains("hi");
        assertThat(result).contains("=== YOUR RESPONSE ===");
    }

    @Test
    void buildConversationPrompt_noSystemPrompt_skipsInstructions() {
        String result = provider.buildConversationPrompt(null, List.of());

        assertThat(result).doesNotContain("=== INSTRUCTIONS ===");
        assertThat(result).contains("=== YOUR RESPONSE ===");
    }
}
