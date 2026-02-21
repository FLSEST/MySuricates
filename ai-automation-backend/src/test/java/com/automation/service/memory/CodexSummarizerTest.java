package com.automation.service.memory;

import com.automation.service.ai.HostCodexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexSummarizerTest {

    @Mock
    private HostCodexService hostCodexService;

    @Mock
    private SummarizationSetupService setupService;

    private CodexSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new CodexSummarizer(hostCodexService, setupService);
    }

    @Test
    void parseResponse_fullFormat() {
        String response = """
                TITLE: React Frontend Patterns
                TAGS: react, typescript, testing
                ---
                ## Project Conventions
                - Build: `npm run build`
                """;

        var result = summarizer.parseResponse(response);

        assertThat(result.title()).isEqualTo("React Frontend Patterns");
        assertThat(result.tags()).containsExactly("react", "typescript", "testing");
        assertThat(result.content()).contains("## Project Conventions");
    }

    @Test
    void parseResponse_fallbackOnMissingFormat() {
        String response = "Plain text without format.";

        var result = summarizer.parseResponse(response);

        assertThat(result.title()).isEqualTo("Workflow Knowledge");
        assertThat(result.tags()).isEmpty();
        assertThat(result.content()).isEqualTo(response);
    }

    @Test
    void summarize_callsCodexWithWorkDir() throws IOException {
        Path workDir = Path.of("/tmp/test-summarization/codex");
        when(setupService.getCodexWorkDir()).thenReturn(workDir);
        when(hostCodexService.execute(anyString(), eq(workDir)))
                .thenReturn("TITLE: Test\nTAGS: test\n---\nCodex content");

        var result = summarizer.summarize("preprocessed input");

        assertThat(result.title()).isEqualTo("Test");
        assertThat(result.content()).isEqualTo("Codex content");
    }

    @Test
    void summarize_ioExceptionPropagated() throws IOException {
        when(setupService.getCodexWorkDir()).thenReturn(Path.of("/tmp/test"));
        when(hostCodexService.execute(anyString(), eq(Path.of("/tmp/test"))))
                .thenThrow(new IOException("Codex timed out"));

        assertThatThrownBy(() -> summarizer.summarize("input"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Codex timed out");
    }
}
