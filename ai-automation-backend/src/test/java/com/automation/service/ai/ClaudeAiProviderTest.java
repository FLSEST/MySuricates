package com.automation.service.ai;

import java.util.Objects;
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
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && Objects.equals(req.systemPrompt(), "system") && req.model() == null && Objects.equals(req.workingDirectory(), "/path") && Objects.equals(req.maxTurns(), 3) && Objects.equals(req.timeoutSeconds(), 300) && req.effort() == null)))
                .thenReturn(hostResult);

        var request = new AiProvider.ReasoningRequest("prompt", "system", "/path", 3, 300);
        AiProvider.ReasoningResult result = provider.reason(request);

        assertThat(result.text()).isEqualTo("reasoning text");
        verify(hostClaudeService).reasonStructured(new AiProvider.ReasoningRequest("prompt", "system", null, "/path", 3, 300, null));
    }

    @Test
    void reason_forwardsEffortToHostService() throws IOException {
        var hostResult = new HostClaudeService.ReasoningResult("out", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.systemPrompt() == null && req.model() == null && req.workingDirectory() == null && req.maxTurns() == null && req.timeoutSeconds() == null && Objects.equals(req.effort(), "high"))))
                .thenReturn(hostResult);

        var request = new AiProvider.ReasoningRequest("prompt", null, null, null, null, "high");
        provider.reason(request);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.systemPrompt() == null && req.model() == null && req.workingDirectory() == null && req.maxTurns() == null && req.timeoutSeconds() == null && Objects.equals(req.effort(), "high")));
    }

    @Test
    void reason_forwardsModelToHostService() throws IOException {
        var hostResult = new HostClaudeService.ReasoningResult("out", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.systemPrompt() == null && Objects.equals(req.model(), "claude-opus-4-6") && req.workingDirectory() == null && req.maxTurns() == null && req.timeoutSeconds() == null && req.effort() == null)))
                .thenReturn(hostResult);

        var request = new AiProvider.ReasoningRequest("prompt", null, "claude-opus-4-6", null, null, null, null);
        provider.reason(request);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.systemPrompt() == null && Objects.equals(req.model(), "claude-opus-4-6") && req.workingDirectory() == null && req.maxTurns() == null && req.timeoutSeconds() == null && req.effort() == null));
    }

    @Test
    void executeAction_delegatesToHostService() throws IOException {
        var hostResult = new HostClaudeService.ActionResult("output", 0, null);
        when(hostClaudeService.executeAction(argThat((AiProvider.ActionRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && Objects.equals(req.model(), "model") && Objects.equals(req.systemPrompt(), "system") && Objects.equals(req.workingDirectory(), "/path") && Objects.equals(req.timeoutSeconds(), 300) && req.priority() == false && req.effort() == null && req.userMcpServers() == false)))
                .thenReturn(hostResult);

        var request = new AiProvider.ActionRequest("prompt", "model", "system", "/path", 300, false);
        AiProvider.ActionResult result = provider.executeAction(request);

        assertThat(result.text()).isEqualTo("output");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void executeAction_forwardsEffortToHostService() throws IOException {
        var hostResult = new HostClaudeService.ActionResult("output", 0, null);
        when(hostClaudeService.executeAction(argThat((AiProvider.ActionRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.model() == null && req.systemPrompt() == null && req.workingDirectory() == null && req.timeoutSeconds() == null && req.priority() == false && Objects.equals(req.effort(), "xhigh") && req.userMcpServers() == false)))
                .thenReturn(hostResult);

        var request = new AiProvider.ActionRequest("prompt", null, null, null, null, false, true, null, null, "xhigh");
        provider.executeAction(request);

        verify(hostClaudeService).executeAction(argThat((AiProvider.ActionRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.model() == null && req.systemPrompt() == null && req.workingDirectory() == null && req.timeoutSeconds() == null && req.priority() == false && Objects.equals(req.effort(), "xhigh") && req.userMcpServers() == false));
    }

    @Test
    void executeAction_forwardsTheBlockOptInForUserMcpServers() throws IOException {
        var hostResult = new HostClaudeService.ActionResult("output", 0, null);
        when(hostClaudeService.executeAction(argThat((AiProvider.ActionRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.model() == null && req.systemPrompt() == null && req.workingDirectory() == null && req.timeoutSeconds() == null && req.priority() == false && req.effort() == null && req.userMcpServers() == true)))
                .thenReturn(hostResult);

        provider.executeAction(new AiProvider.ActionRequest("prompt", null, null, null, null, false, true, null, null,
                null, true));

        verify(hostClaudeService).executeAction(argThat((AiProvider.ActionRequest req) -> req != null && Objects.equals(req.prompt(), "prompt") && req.model() == null && req.systemPrompt() == null && req.workingDirectory() == null && req.timeoutSeconds() == null && req.priority() == false && req.effort() == null && req.userMcpServers() == true));
    }

    @Test
    void buildConversationPrompt_usesTheSharedFormat() {
        List<ConversationPrompt.Turn> history = List.of(new ConversationPrompt.Turn("user", "hello"));

        assertThat(provider.buildConversationPrompt("system", history)).isEqualTo(ConversationPrompt.build(history));
    }
}
