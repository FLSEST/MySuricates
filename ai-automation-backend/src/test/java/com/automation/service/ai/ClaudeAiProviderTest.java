package com.automation.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeAiProviderTest {

    @Mock private HostClaudeService hostClaudeService;

    private ClaudeAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ClaudeAiProvider(hostClaudeService);
    }

    @Test
    void getId_returnsCLAUDE() {
        assertThat(provider.getId()).isEqualTo("CLAUDE");
    }

    @Test
    void getCapabilities_returnsFull() {
        AiProviderCapabilities caps = provider.getCapabilities();
        assertThat(caps).isEqualTo(AiProviderCapabilities.full());
    }

    @Test
    void reason_delegatesToHostService() throws IOException {
        var hostResult = new HostClaudeService.ReasoningResult("reasoning text", null);
        when(hostClaudeService.reasonStructured(
                eq("prompt"), eq("system"), eq("/path"), eq(3), eq(300)))
                .thenReturn(hostResult);

        var request = new AiProvider.ReasoningRequest("prompt", "system", "/path", 3, 300);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("reasoning text");
        verify(hostClaudeService).reasonStructured("prompt", "system", "/path", 3, 300);
    }

    @Test
    void executeAction_delegatesToHostService() throws IOException {
        var hostResult = new HostClaudeService.ActionResult("output", 0, null);
        when(hostClaudeService.executeAction(
                eq("prompt"), eq("model"), eq("system"), eq("/path"), eq(300), eq(false),
                any(), any()))
                .thenReturn(hostResult);

        var request = new AiProvider.ActionRequest("prompt", "model", "system", "/path", 300, false);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("output");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void buildConversationPrompt_delegatesToHostService() {
        List<HostClaudeService.Turn> history = List.of(
                new HostClaudeService.Turn("user", "hello"));
        when(hostClaudeService.buildConversationPrompt("system", history)).thenReturn("formatted");

        String result = provider.buildConversationPrompt("system", history);

        assertThat(result).isEqualTo("formatted");
    }
}
