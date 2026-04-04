package com.automation.service.learning;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowStatsServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ExecutionInsightsService insightsService;
    @Mock private AgenticLearningService agenticService;

    private WorkflowStatsService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowStatsService(runRepository);
    }

    @Test
    void getWorkflowStats_noRuns_returnsZero() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> result = service.getWorkflowStats("wf-1");

        assertThat(result.get("totalRuns")).isEqualTo(0);
    }

    @Test
    void getWorkflowStats_withRuns_calculatesAll() {
        WorkflowRun r1 = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(1000L).userRating(4).build();
        WorkflowRun r2 = WorkflowRun.builder().id("r2").status(RunStatus.FAILED)
                .durationMs(2000L).build();
        WorkflowRun r3 = WorkflowRun.builder().id("r3").status(RunStatus.COMPLETED)
                .durationMs(3000L).userRating(5).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1"))
                .thenReturn(List.of(r1, r2, r3));

        Map<String, Object> result = service.getWorkflowStats("wf-1");

        assertThat(result.get("totalRuns")).isEqualTo(3);
        assertThat((long) result.get("completed")).isEqualTo(2);
        assertThat((long) result.get("failed")).isEqualTo(1);
        assertThat((double) result.get("successRate")).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.01));
        assertThat((long) result.get("ratedRuns")).isEqualTo(2);
    }

    @Test
    void getStepAnalytics_groupsByStepIndex() {
        StepResult s1 = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .durationMs(100L).input("echo hi").build();
        StepResult s2 = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                .durationMs(200L).input("echo hi").build();
        StepResult s3 = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .durationMs(300L).input("echo bye").build();

        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(s1, s2, s3))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        List<Map<String, Object>> result = service.getStepAnalytics("wf-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("stepIndex")).isEqualTo(0);
        assertThat((long) result.get(0).get("totalExecutions")).isEqualTo(2);
        assertThat((double) result.get(0).get("failureRate")).isEqualTo(0.5);
    }

    @Test
    void getOptimizationSuggestions_lowSuccessRate_suggests() {
        List<WorkflowRun> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(WorkflowRun.builder().id("r" + i)
                    .status(i < 1 ? RunStatus.COMPLETED : RunStatus.FAILED)
                    .durationMs(1000L).build());
        }
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);
        when(insightsService.getExecutionInsights("wf-1")).thenReturn(Map.of("runsWithInsights", 0));
        when(agenticService.getToolPatterns("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "LOW_SUCCESS_RATE".equals(s.get("type")));
    }

    @Test
    void getOptimizationSuggestions_stepFailureHotspot_suggests() {
        StepResult s1 = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                .durationMs(100L).input("bad cmd").build();
        StepResult s2 = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                .durationMs(100L).input("bad cmd").build();
        StepResult s3 = StepResult.builder().stepIndex(0).status(StepStatus.FAILED)
                .durationMs(100L).input("bad cmd").build();

        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.FAILED)
                .durationMs(100L).steps(new ArrayList<>(List.of(s1, s2, s3))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(insightsService.getExecutionInsights("wf-1")).thenReturn(Map.of("runsWithInsights", 0));
        when(agenticService.getToolPatterns("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "STEP_FAILURE_HOTSPOT".equals(s.get("type")));
    }

    @Test
    void getOptimizationSuggestions_highVariance_suggests() {
        List<WorkflowRun> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(WorkflowRun.builder().id("r" + i).status(RunStatus.COMPLETED)
                    .durationMs(i == 0 ? 1000L : 10000L).build());
        }
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);
        when(insightsService.getExecutionInsights("wf-1")).thenReturn(Map.of("runsWithInsights", 0));
        when(agenticService.getToolPatterns("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "HIGH_VARIANCE".equals(s.get("type")));
    }

    @Test
    void getOptimizationSuggestions_highCost_suggests() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(1000L).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(insightsService.getExecutionInsights("wf-1"))
                .thenReturn(Map.of("runsWithInsights", 5, "avgCostPerRunUsd", 0.50));
        when(agenticService.getToolPatterns("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "HIGH_COST".equals(s.get("type")));
    }

    @Test
    void calculateStepStats_correctAggregation() {
        List<StepResult> steps = List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).durationMs(100L).input("cmd").build(),
                StepResult.builder().stepIndex(0).status(StepStatus.FAILED).durationMs(200L).input("cmd").build());

        Map<String, Object> stats = service.calculateStepStats(0, steps);

        assertThat((long) stats.get("totalExecutions")).isEqualTo(2);
        assertThat((long) stats.get("failures")).isEqualTo(1);
        assertThat((double) stats.get("failureRate")).isEqualTo(0.5);
        assertThat(stats.get("command")).isEqualTo("cmd");
    }

    @Test
    void runStatistics_allMethods_correct() {
        WorkflowRun r1 = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(1000L).userRating(3).build();
        WorkflowRun r2 = WorkflowRun.builder().id("r2").status(RunStatus.COMPLETED)
                .durationMs(3000L).userRating(5).build();

        var stats = new WorkflowStatsService.RunStatistics(List.of(r1, r2));

        assertThat(stats.countByStatus(RunStatus.COMPLETED)).isEqualTo(2);
        assertThat(stats.getSuccessRate()).isEqualTo(1.0);
        assertThat(stats.getAvgDuration()).hasValue(2000.0);
        assertThat(stats.getMinDuration()).hasValue(1000L);
        assertThat(stats.getMaxDuration()).hasValue(3000L);
        assertThat(stats.getAvgRating()).hasValue(4.0);
        assertThat(stats.getRatedCount()).isEqualTo(2);
    }

    @Test
    void getOptimizationSuggestions_slowStep_suggests() {
        StepResult slowStep = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .durationMs(40_000L).input("backup").build();
        StepResult fastStep = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .durationMs(5_000L).input("notify").build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(45_000L).steps(new ArrayList<>(List.of(slowStep, fastStep))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(insightsService.getExecutionInsights("wf-1")).thenReturn(Map.of("runsWithInsights", 0));
        when(agenticService.getToolPatterns("wf-1")).thenReturn(Map.of("totalAgenticRuns", 0));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "SLOW_STEP".equals(s.get("type")));
    }

    @Test
    void getOptimizationSuggestions_agenticCost_suggests() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(1000L).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run, run, run));
        when(insightsService.getExecutionInsights("wf-1")).thenReturn(Map.of("runsWithInsights", 0));
        when(agenticService.getToolPatterns("wf-1"))
                .thenReturn(Map.of("totalAgenticRuns", 3, "totalCostUsd", 0.30));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).anyMatch(s -> "AGENTIC_COST".equals(s.get("type")));
    }

    @Test
    void getOptimizationSuggestions_optionalCostInsightsFailure_isIgnored() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .durationMs(1000L).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(insightsService.getExecutionInsights(anyString())).thenThrow(new RuntimeException("offline"));

        List<Map<String, Object>> suggestions = service.getOptimizationSuggestions("wf-1", insightsService, agenticService);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void calculateStepStats_withoutInput_usesUnknownAndZeroDurations() {
        List<StepResult> steps = List.of(
                StepResult.builder().stepIndex(2).status(StepStatus.COMPLETED).build(),
                StepResult.builder().stepIndex(2).status(StepStatus.FAILED).build());

        Map<String, Object> stats = service.calculateStepStats(2, steps);

        assertThat(stats.get("command")).isEqualTo("unknown");
        assertThat(stats.get("avgDurationMs")).isEqualTo(0.0);
        assertThat(stats.get("maxDurationMs")).isEqualTo(0L);
        assertThat(stats.get("failureRate")).isEqualTo(0.5);
    }
}
