package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import com.automation.service.ai.AiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.automation.config.ExecutionDefaults.AGENTIC_ACTION_COMPRESS_THRESHOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticActionRunnerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AgenticPromptBuilder promptBuilder;
    @Mock private AiProvider provider;

    private AgenticActionRunner runner;
    private ExecutionContext context;
    private AgenticBlockConfig cfg;

    @BeforeEach
    void setUp() {
        runner = new AgenticActionRunner(promptBuilder, new AgenticEventBroadcaster(messagingTemplate));
        context = ExecutionContext.builder()
                .containerId("c").workflowRunId("run-1").projectPath("/proj").build();
        cfg = AgenticBlockConfig.from(Map.of("goal", "g"), context);
    }

    private Map<String, Object> iterData() {
        return new LinkedHashMap<>();
    }

    @Test
    void run_providerThrows_returnsExecutionErrorWithExitCodeMinusOne() throws IOException {
        when(provider.executeAction(any())).thenThrow(new IOException("boom"));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, "do it",
                provider, null, cfg, new AgenticTokenTracker(), iterData());

        assertThat(outcome.output()).isEqualTo("Execution error: boom");
        assertThat(outcome.exitCode()).isEqualTo(-1);
        assertThat(outcome.action()).isEqualTo("do it");
        // No compression attempted for a short action, and the failed call must not blow up captureActMeta.
        verifyNoInteractions(promptBuilder);
    }

    @Test
    void run_oversizedAction_compressesViaPromptBuilder_andExecutesCompressed() throws IOException {
        String oversized = "x".repeat(AGENTIC_ACTION_COMPRESS_THRESHOLD + 1);
        when(promptBuilder.compressAction(eq(oversized), eq(provider), eq(context.getModel())))
                .thenReturn("compressed action");
        when(provider.executeAction(any()))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, oversized,
                provider, null, cfg, new AgenticTokenTracker(), iterData());

        verify(promptBuilder).compressAction(oversized, provider, context.getModel());
        assertThat(outcome.action()).isEqualTo("compressed action");
        assertThat(outcome.output()).isEqualTo("done");
        assertThat(outcome.exitCode()).isEqualTo(0);

        ArgumentCaptor<AiProvider.ActionRequest> captor = ArgumentCaptor.forClass(AiProvider.ActionRequest.class);
        verify(provider).executeAction(captor.capture());
        assertThat(captor.getValue().prompt()).isEqualTo("compressed action");
    }

    @Test
    void run_compressionFails_fallsBackToTruncatedAction() throws IOException {
        String oversized = "y".repeat(AGENTIC_ACTION_COMPRESS_THRESHOLD + 500);
        when(promptBuilder.compressAction(anyString(), any(), any()))
                .thenThrow(new IOException("compression failed"));
        when(provider.executeAction(any()))
                .thenReturn(new AiProvider.ActionResult("done", 0, null));

        AgenticActionRunner.ActOutcome outcome = runner.run(context, 0, 1, oversized,
                provider, null, cfg, new AgenticTokenTracker(), iterData());

        assertThat(outcome.action()).startsWith("y".repeat(AGENTIC_ACTION_COMPRESS_THRESHOLD));
        assertThat(outcome.action()).contains("[TRUNCATED: original was " + oversized.length() + " chars]");
    }
}
