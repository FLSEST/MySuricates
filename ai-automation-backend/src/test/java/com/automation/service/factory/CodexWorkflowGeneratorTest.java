package com.automation.service.factory;

import com.automation.service.ai.HostCodexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexWorkflowGeneratorTest {

    @Mock
    private HostCodexService hostCodexService;

    @Mock
    private FactorySetupService setupService;

    private CodexWorkflowGenerator generator;

    private static final Path WORK_DIR = Path.of("/tmp/codex");

    @BeforeEach
    void setUp() {
        lenient().when(setupService.getCodexWorkDir()).thenReturn(WORK_DIR);
        generator = new CodexWorkflowGenerator(hostCodexService, setupService);
    }

    // --- Annotation tests ---

    @Test
    void should_haveComponentAnnotation_when_classIsInspected() {
        assertThat(CodexWorkflowGenerator.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    void should_implementWorkflowTextGenerator_when_classIsInspected() {
        assertThat(WorkflowTextGenerator.class.isAssignableFrom(CodexWorkflowGenerator.class)).isTrue();
    }

    // --- generate() delegation tests ---

    @Test
    void generate_returnsOutputFromHostCodexService() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("codex output");

        String result = generator.generate("create workflow", "system instructions", 300);

        assertThat(result).isEqualTo("codex output");
    }

    @Test
    void generate_mergesSystemPromptIntoPrompt_when_systemPromptIsNotNull() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("ok");

        generator.generate("user prompt", "system instructions", 60);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hostCodexService).execute(promptCaptor.capture(), eq(WORK_DIR), eq(60));
        String merged = promptCaptor.getValue();
        assertThat(merged).contains("system instructions");
        assertThat(merged).contains("user prompt");
    }

    @Test
    void generate_mergedPromptSeparatedByDelimiter_when_systemPromptNotNull() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("ok");

        generator.generate("user prompt", "system instructions", 60);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hostCodexService).execute(promptCaptor.capture(), eq(WORK_DIR), eq(60));
        assertThat(promptCaptor.getValue()).contains("---");
    }

    @Test
    void generate_systemPromptAppearsBeforeUserPrompt_when_merged() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("ok");

        generator.generate("user content", "system content", 60);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hostCodexService).execute(promptCaptor.capture(), eq(WORK_DIR), eq(60));
        String merged = promptCaptor.getValue();
        assertThat(merged.indexOf("system content")).isLessThan(merged.indexOf("user content"));
    }

    @Test
    void generate_usesOnlyUserPrompt_when_systemPromptIsNull() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("ok");

        generator.generate("just the prompt", null, 60);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hostCodexService).execute(promptCaptor.capture(), eq(WORK_DIR), eq(60));
        assertThat(promptCaptor.getValue()).isEqualTo("just the prompt");
    }

    @Test
    void generate_passesWorkDirFromSetupService() throws IOException {
        Path customDir = Path.of("/custom/codex");
        when(setupService.getCodexWorkDir()).thenReturn(customDir);
        when(hostCodexService.execute(anyString(), eq(customDir), anyInt())).thenReturn("ok");

        generator.generate("prompt", "system", 60);

        verify(hostCodexService).execute(anyString(), eq(customDir), anyInt());
    }

    @Test
    void generate_passesTimeoutToHostCodexService() throws IOException {
        when(hostCodexService.execute(anyString(), any(), eq(600))).thenReturn("ok");

        generator.generate("prompt", "system", 600);

        verify(hostCodexService).execute(anyString(), any(), eq(600));
    }

    @Test
    void generate_propagatesIOException_when_hostCodexServiceThrows() throws IOException {
        when(hostCodexService.execute(anyString(), any(), anyInt()))
                .thenThrow(new IOException("Codex CLI unavailable"));

        assertThatThrownBy(() -> generator.generate("prompt", "system", 60))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Codex CLI unavailable");
    }

    @Test
    void generate_handlesEmptySystemPrompt_asMerge() throws IOException {
        when(hostCodexService.execute(anyString(), eq(WORK_DIR), anyInt())).thenReturn("result");

        generator.generate("user prompt", "", 60);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(hostCodexService).execute(promptCaptor.capture(), eq(WORK_DIR), eq(60));
        // Empty string is not null — will be merged with separator
        assertThat(promptCaptor.getValue()).contains("user prompt");
    }
}
