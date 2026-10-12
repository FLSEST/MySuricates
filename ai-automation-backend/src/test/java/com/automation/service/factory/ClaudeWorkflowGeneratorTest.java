package com.automation.service.factory;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.HostClaudeService;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeWorkflowGeneratorTest {

    @Mock
    private HostClaudeService hostClaudeService;

    private ClaudeWorkflowGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ClaudeWorkflowGenerator(hostClaudeService);
    }

    // --- Annotation tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(ClaudeWorkflowGenerator.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    void should_implementWorkflowTextGenerator_when_classIsInspected() {
        assertThat(WorkflowTextGenerator.class.isAssignableFrom(ClaudeWorkflowGenerator.class)).isTrue();
    }

    // --- generate() delegation tests ---

    @Test
    void generate_returnsTextFromReasoningResult() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("generated workflow", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1) && Objects.equals(req.timeoutSeconds(), 300))))
                .thenReturn(result);

        String output = generator.generate("create a workflow", "system instructions", 300);

        assertThat(output).isEqualTo("generated workflow");
    }

    @Test
    void generate_passesPromptToHostClaudeService() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("ok", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "my prompt") && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1))))
                .thenReturn(result);

        generator.generate("my prompt", "system", 60);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && Objects.equals(req.prompt(), "my prompt") && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1) && Objects.equals(req.timeoutSeconds(), 60)));
    }

    @Test
    void generate_passesSystemPromptToHostClaudeService() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("ok", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && Objects.equals(req.systemPrompt(), "my system prompt") && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1))))
                .thenReturn(result);

        generator.generate("prompt", "my system prompt", 60);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && Objects.equals(req.systemPrompt(), "my system prompt") && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1) && Objects.equals(req.timeoutSeconds(), 60)));
    }

    @Test
    void generate_passesTimeoutToHostClaudeService() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("ok", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1) && Objects.equals(req.timeoutSeconds(), 600))))
                .thenReturn(result);

        generator.generate("prompt", "system", 600);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1) && Objects.equals(req.timeoutSeconds(), 600)));
    }

    @Test
    void generate_usesMaxTurns1_when_callingHostClaudeService() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("ok", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1))))
                .thenReturn(result);

        generator.generate("prompt", "system", 120);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null && Objects.equals(req.maxTurns(), 1)));
    }

    @Test
    void generate_propagatesIOException_when_hostClaudeServiceThrows() throws IOException {
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null)))
                .thenThrow(new IOException("Claude CLI unavailable"));

        assertThatThrownBy(() -> generator.generate("prompt", "system", 60))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Claude CLI unavailable");
    }

    @Test
    void generate_handlesEmptyTextResponse() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null)))
                .thenReturn(result);

        String output = generator.generate("prompt", "system", 60);

        assertThat(output).isEmpty();
    }

    @Test
    void generate_passesNullWorkingDirectory_to_hostClaudeService() throws IOException {
        HostClaudeService.ReasoningResult result = new HostClaudeService.ReasoningResult("result", null);
        when(hostClaudeService.reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null)))
                .thenReturn(result);

        generator.generate("prompt", "system", 60);

        verify(hostClaudeService).reasonStructured(argThat((AiProvider.ReasoningRequest req) -> req != null && req.prompt() != null && req.systemPrompt() != null && req.workingDirectory() == null));
    }
}
