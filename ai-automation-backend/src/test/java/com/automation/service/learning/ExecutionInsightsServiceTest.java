package com.automation.service.learning;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionInsightsServiceTest {

    @Mock private WorkflowRunRepository runRepository;

    private ExecutionInsightsService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionInsightsService(runRepository);
    }

    @Test
    void getExecutionInsights_noRuns_returnsZero() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> result = service.getExecutionInsights("wf-1");

        assertThat(result.get("totalRuns")).isEqualTo(0);
    }

    @Test
    void getExecutionInsights_withRuns_aggregatesTokensAndCost() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(Map.of("costUsd", 0.05, "inputTokens", 1000L, "outputTokens", 500L, "model", "claude-sonnet-4-6"))
                .build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        Map<String, Object> result = service.getExecutionInsights("wf-1");

        assertThat(result.get("totalRuns")).isEqualTo(1);
        assertThat((double) result.get("totalCostUsd")).isEqualTo(0.05);
        assertThat((long) result.get("totalInputTokens")).isEqualTo(1000L);
        assertThat((long) result.get("totalOutputTokens")).isEqualTo(500L);
        @SuppressWarnings("unchecked")
        Set<String> models = (Set<String>) result.get("modelsUsed");
        assertThat(models).contains("claude-sonnet-4-6");
    }

    /**
     * TODO 20861200: the workflow insights sum the cache and thinking figures like the run insights —
     * before, only the run insights had them. Steps without them (Codex, Pi, older runs) add nothing.
     */
    @Test
    void getExecutionInsights_sumsCacheAndThinkingTokens() {
        StepResult claude = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 2, "outputTokens", 918, "cacheReadInputTokens", 13720,
                        "cacheCreationInputTokens", 48462, "thinkingTokens", 377))
                .build();
        StepResult codex = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 400, "outputTokens", 100, "estimated", true))
                .build();
        StepResult later = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 3, "outputTokens", 50, "cacheReadInputTokens", 280, "thinkingTokens", 23))
                .build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(
                WorkflowRun.builder().id("r2").status(RunStatus.COMPLETED).steps(new ArrayList<>(List.of(later))).build(),
                WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED).steps(new ArrayList<>(List.of(claude, codex))).build()));

        Map<String, Object> result = service.getExecutionInsights("wf-1");

        assertThat(result).containsEntry("totalInputTokens", 405L)
                .containsEntry("totalCacheReadInputTokens", 14000L)
                .containsEntry("totalCacheCreationInputTokens", 48462L)
                .containsEntry("totalThinkingTokens", 400L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRunInsights_reportsThinkingTokensPerStepAndInTotal() {
        StepResult claude = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 2, "outputTokens", 918, "thinkingTokens", 377)).build();
        StepResult pi = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 400, "outputTokens", 100, "estimated", true)).build();
        when(runRepository.findById("r1")).thenReturn(Optional.of(WorkflowRun.builder().id("r1")
                .status(RunStatus.COMPLETED).steps(new ArrayList<>(List.of(claude, pi))).build()));

        Map<String, Object> result = service.getRunInsights("r1");

        assertThat(result).containsEntry("totalThinkingTokens", 377L).containsEntry("totalOutputTokens", 1018L);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(steps.get(0)).containsEntry("thinkingTokens", 377L);
        assertThat(steps.get(1)).doesNotContainKey("thinkingTokens");
    }

    @Test
    void getExecutionInsights_noMetadata_handlesGracefully() {
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        Map<String, Object> result = service.getExecutionInsights("wf-1");

        assertThat(result.get("totalRuns")).isEqualTo(1);
        assertThat((double) result.get("totalCostUsd")).isEqualTo(0.0);
    }

    @Test
    void getRunInsights_validRun_returnsStepDetails() {
        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1500L)
                .metadata(Map.of("costUsd", 0.02, "inputTokens", 500L, "outputTokens", 200L))
                .build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(2000L).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findById("r1")).thenReturn(Optional.of(run));

        Map<String, Object> result = service.getRunInsights("r1");

        assertThat(result.get("runId")).isEqualTo("r1");
        assertThat((double) result.get("totalCostUsd")).isEqualTo(0.02);
        assertThat((List<?>) result.get("steps")).hasSize(1);
    }

    /** Cache figures per step and summed per run; a step without them gets no key (TODO 20adda30). */
    @Test
    @SuppressWarnings("unchecked")
    void getRunInsights_reportsCacheTokensBesideTheUncachedInput() {
        StepResult claude = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 2, "outputTokens", 918, "cacheReadInputTokens", 13720,
                        "cacheCreationInputTokens", 48462))
                .build();
        StepResult codex = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED)
                .metadata(Map.of("inputTokens", 400, "outputTokens", 100, "estimated", true))
                .build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(claude, codex))).build();
        when(runRepository.findById("r1")).thenReturn(Optional.of(run));

        Map<String, Object> result = service.getRunInsights("r1");

        assertThat(result).containsEntry("totalInputTokens", 402L)
                .containsEntry("totalCacheReadInputTokens", 13720L)
                .containsEntry("totalCacheCreationInputTokens", 48462L);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(steps.get(0)).containsEntry("cacheReadInputTokens", 13720L)
                .containsEntry("cacheCreationInputTokens", 48462L);
        assertThat(steps.get(1)).doesNotContainKeys("cacheReadInputTokens", "cacheCreationInputTokens");
    }

    @Test
    void getRunInsights_notFound_throwsException() {
        when(runRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRunInsights("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rateRun_validRating_saves() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED).build();
        when(runRepository.findById("r1")).thenReturn(Optional.of(run));

        service.rateRun("r1", 4);

        assertThat(run.getUserRating()).isEqualTo(4);
        verify(runRepository).save(run);
    }

    @Test
    void rateRun_invalidRating_throwsException() {
        assertThatThrownBy(() -> service.rateRun("r1", 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rateRun("r1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rateRun_notFound_throwsException() {
        when(runRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rateRun("missing", 3))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
