package com.automation.executor.blocks;

import com.automation.config.CompressionConfig;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.compress.CompressAiPrompt;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompressAiBlockExecutorTest {

    @Mock private CommandExecutorService commandExecutor;
    @Mock private AiProviderRegistry providers;
    @Mock private AiProvider provider;

    private CompressionConfig config;
    private CompressAiBlockExecutor executor;
    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        config = new CompressionConfig();
        executor = new CompressAiBlockExecutor(commandExecutor, providers, config);
        ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        ctx.setProjectPath("/projekt");
        when(providers.resolve(any())).thenReturn(provider);
        when(provider.getId()).thenReturn("CLAUDE");
        Map<String, Object> md = new HashMap<>(Map.of("blockType", "shell", "exitCode", 1));
        StepResult source = StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output("LOG " + "x".repeat(100))
                .durationMs(5_000L).metadata(md).build();
        ctx.recordOutput("backend_tests", source.getOutput());
        ctx.getSourceSteps().record("backend_tests", source);
    }

    private static BlockDefinition block(Map<String, Object> cfg) {
        return BlockDefinition.builder().type("compress-ai").name("kern").config(cfg).build();
    }

    private static StepResult aiAnswer(StepStatus status, String output) {
        return StepResult.builder().status(status).input("PROMPT MIT QUELLE").output(output)
                .metadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE", "model", "haiku")))).build();
    }

    @Test
    void supportsCompressAiOnly() {
        assertThat(executor.supports("compress-ai")).isTrue();
        assertThat(executor.supports("compress")).isFalse();
    }

    @Test
    void callsProviderWithoutTools_inNeutralWorkdir_withFixedSystemPrompt() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "2 Tests fehlgeschlagen: A, B"));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "instruction", "Nenne Fehler {{ open_todos }}")), ctx);

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        HostExecutionRequest req = captor.getValue();
        assertThat(req.allowedTools()).isEqualTo(AiProvider.NO_TOOLS);
        Path workdir = Path.of(req.projectPath());
        assertThat(workdir.getFileName().toString()).startsWith("mysuricates-compress-ai-");
        assertThat(req.systemPrompt()).isEqualTo(CompressAiPrompt.SYSTEM);
        String prompt = String.valueOf(req.block().getConfig().get("command"));
        assertThat(prompt).contains("<quelle>\nLOG ").contains("Nenne Fehler {{ open_todos }}");

        assertThat(r.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(r.getOutput()).startsWith("## backend_tests — FAILED · exit 1 · 5 s")
                .contains("\nSummary: AI (CLAUDE)\n\n2 Tests fehlgeschlagen: A, B");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest").containsKey("compression");
        assertThat(r.getInput()).doesNotContain("x".repeat(100)).contains("Nenne Fehler");
        assertThat(Files.exists(workdir)).as("per-call workdir is deleted again after execute").isFalse();
    }

    @Test
    void inputOverLimit_failsWithoutCall() {
        config.setMaxAiInputChars(50);

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("put a compress block before this one");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void providerOtherThanClaude_failsWithoutCall() {
        when(provider.getId()).thenReturn("CODEX");

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("\"provider\": \"CLAUDE\"");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void aiFailure_isFailed_noFallbackToOriginal() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.FAILED, "Error: timeout"));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).contains("AI compression failed").doesNotContain("x".repeat(100));
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest");
    }

    @Test
    void blockModelEffortAndProvider_reachTheRequest() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "kurz"));

        executor.execute(block(Map.of("source", "backend_tests", "model", "haiku", "effort", "low", "provider", "CLAUDE")), ctx);

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("haiku");
        assertThat(captor.getValue().effort()).isEqualTo("low");
        assertThat(captor.getValue().providerId()).isEqualTo("CLAUDE");
    }

    @Test
    void blankAnswer_isFailed_keepsAiMetadata() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "  \n "));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).isEqualTo("AI compression failed: AI returned no answer");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("aiRequest").doesNotContainKey("compression");
    }

    @Test
    void aiFailure_outputIsNullSafeAndCappedTo2000Chars() {
        String prefix = "AI compression failed: ";
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.FAILED, "E".repeat(9_000) + "ENDE"));

        StepResult longFailure = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(longFailure.getOutput()).startsWith(prefix).endsWith("ENDE").contains("chars omitted");
        assertThat(longFailure.getOutput().length()).isLessThanOrEqualTo(prefix.length() + 2_000);

        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.FAILED, null));

        StepResult nullFailure = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(nullFailure.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(nullFailure.getOutput()).startsWith(prefix).doesNotContain("null");
    }

    @Test
    void unexpectedRuntimeException_afterCall_isFailed_keepsAiMetadata() {
        StepResult broken = mock(StepResult.class);
        when(broken.getMetadata()).thenReturn(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE"))));
        when(broken.getStatus()).thenThrow(new IllegalStateException("kaputt"));
        when(commandExecutor.executeStepOnHost(any())).thenReturn(broken);

        StepResult r = executor.execute(block(Map.of("source", "backend_tests")), ctx);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(r.getOutput()).isEqualTo("Compression failed: IllegalStateException: kaputt");
        assertThat(r.getMetadata()).containsEntry("derivedOutput", true).containsKey("error").containsKey("aiRequest");
    }

    @Test
    void tooLongAnswer_isCappedDeterministically() {
        when(commandExecutor.executeStepOnHost(any())).thenReturn(aiAnswer(StepStatus.COMPLETED, "y".repeat(5000)));

        StepResult r = executor.execute(block(Map.of("source", "backend_tests", "max_chars", 1000)), ctx);

        assertThat(r.getOutput().length()).isLessThanOrEqualTo(1000);
        assertThat(((Map<?, ?>) r.getMetadata().get("compression")).get("capped")).isEqualTo(true);
    }
}
