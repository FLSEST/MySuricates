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
import static org.mockito.Mockito.verify;
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
        when(hostCodexService.execute(contains("SYSTEM INSTRUCTIONS"), eq(Path.of("/path")), eq(120), eq(true)))
                .thenReturn("codex output");

        var request = new AiProvider.ReasoningRequest("prompt", "system", "/path", 3, null);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("codex output");
    }

    @Test
    void reason_alwaysUsesReadOnly() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), any(), eq(120), eq(true)))
                .thenReturn("output");

        var request = new AiProvider.ReasoningRequest("prompt", null, null, 3, null);
        provider.reason(request);

        verify(hostCodexService).execute(anyString(), isNull(), eq(120), eq(true));
    }

    @Test
    void reason_usesExplicitTimeout() throws IOException {
        when(hostCodexService.execute(anyString(), any(), eq(60), eq(true)))
                .thenReturn("output");

        var request = new AiProvider.ReasoningRequest("prompt", null, null, 3, 60);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("output");
    }

    @Test
    void executeAction_readOnly_passesThroughToHostService() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120), eq(true)))
                .thenReturn("action output");

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, true);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("action output");
        assertThat(result.exitCode()).isEqualTo(0);
        verify(hostCodexService).execute(anyString(), isNull(), eq(120), eq(true));
    }

    @Test
    void executeAction_withEffort_isIgnoredWithoutError() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120), eq(true)))
                .thenReturn("action output");

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, true,
                null, null, "high");
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.text()).isEqualTo("action output");
    }

    @Test
    void getCapabilities_doesNotSupportEffort() {
        assertThat(provider.getCapabilities().supportsEffort()).isFalse();
    }

    @Test
    void executeAction_notReadOnly_passesFalse() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120), eq(false)))
                .thenReturn("action output");

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, false);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("action output");
        verify(hostCodexService).execute(anyString(), isNull(), eq(120), eq(false));
    }

    @Test
    void executeAction_ioException_returnsExitCode1() throws IOException {
        when(summarizationConfig.getTimeoutSeconds()).thenReturn(120);
        when(hostCodexService.execute(anyString(), isNull(), eq(120), eq(true)))
                .thenThrow(new IOException("CLI failed"));

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, true);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.text()).contains("CLI failed");
    }

    @Test
    void reason_readOnly_prependsInlineInstruction() throws IOException {
        when(hostCodexService.execute(argThat(s -> s.startsWith("Output your entire response")),
                any(), anyInt(), eq(true))).thenReturn("output");

        var request = new AiProvider.ReasoningRequest("my prompt", null, null, null, 60);
        provider.reason(request);

        verify(hostCodexService).execute(
                argThat(s -> s.contains("Output your entire response as direct text")
                        && s.contains("Do NOT write any files")
                        && s.contains("my prompt")),
                isNull(), eq(60), eq(true));
    }

    @Test
    void executeAction_notReadOnly_noInlineInstruction() throws IOException {
        when(hostCodexService.execute(anyString(), any(), anyInt(), eq(false)))
                .thenReturn("output");

        var request = new AiProvider.ActionRequest("my prompt", null, null, null, 60, false, false);
        provider.executeAction(request);

        verify(hostCodexService).execute(
                argThat(s -> !s.contains("Do NOT write any files") && s.contains("my prompt")),
                isNull(), eq(60), eq(false));
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
