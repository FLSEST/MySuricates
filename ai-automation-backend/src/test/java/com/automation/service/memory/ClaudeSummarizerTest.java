package com.automation.service.memory;

import com.automation.service.ai.HostClaudeService;
import com.automation.service.cli.ClaudeJsonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeSummarizerTest {

    @Mock
    private HostClaudeService hostClaudeService;

    @Mock
    private SummarizationSetupService setupService;

    private ClaudeSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new ClaudeSummarizer(hostClaudeService, setupService);
    }

    @Test
    void parseResponse_fullFormat() {
        String response = """
                TITLE: Spring Boot API Conventions
                TAGS: spring, api, testing, docker
                ---
                ## Projekt-Konventionen
                - Build: `mvn clean package`
                - Config: `src/main/resources/application.yml`

                ## Erfolgreiche Strategien
                - Always run tests before commit
                """;

        var result = summarizer.parseResponse(response);

        assertThat(result.title()).isEqualTo("Spring Boot API Conventions");
        assertThat(result.tags()).containsExactly("spring", "api", "testing", "docker");
        assertThat(result.content()).contains("## Projekt-Konventionen");
        assertThat(result.content()).contains("## Erfolgreiche Strategien");
    }

    @Test
    void parseResponse_fallbackOnMissingFormat() {
        String response = "Just some plain text without the expected format.";

        var result = summarizer.parseResponse(response);

        assertThat(result.title()).isEqualTo("Workflow Knowledge");
        assertThat(result.tags()).isEmpty();
        assertThat(result.content()).isEqualTo(response);
    }

    @Test
    void parseResponse_titleTruncated() {
        String response = "TITLE: " + "A".repeat(80) + "\nTAGS: a\n---\ncontent";

        var result = summarizer.parseResponse(response);

        assertThat(result.title()).hasSize(60);
    }

    @Test
    void summarize_callsHostClaudeWithWorkDir() throws IOException {
        when(setupService.getClaudeWorkDir()).thenReturn(Path.of("/tmp/test-summarization/claude"));
        when(hostClaudeService.reasonStructured(anyString(), isNull(), anyString(), eq(1)))
                .thenReturn(new HostClaudeService.ReasoningResult(
                        "TITLE: Test\nTAGS: test\n---\nTest content",
                        new ClaudeJsonResponse(null, null, null, null, null, null, null, null, null)));

        var result = summarizer.summarize("preprocessed input");

        assertThat(result.title()).isEqualTo("Test");
        assertThat(result.content()).isEqualTo("Test content");
    }
}
