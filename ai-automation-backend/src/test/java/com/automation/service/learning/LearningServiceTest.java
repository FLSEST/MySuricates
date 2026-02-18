package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowMapper workflowMapper;

    @InjectMocks
    private LearningService learningService;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .blocks(List.of(
                        BlockDefinition.builder().type("simple").build(),
                        BlockDefinition.builder().type("retry").build()
                ))
                .build();
    }

    // --- getWorkflowStats ---

    @Test
    void getWorkflowStats_noRuns_returnsZeroTotal() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> stats = learningService.getWorkflowStats("wf-1");

        assertThat(stats.get("totalRuns")).isEqualTo(0);
        assertThat(stats).hasSize(1);
    }

    @Test
    void getWorkflowStats_withRuns_computesCorrectStats() {
        List<WorkflowRun> runs = List.of(
                createRun(RunStatus.COMPLETED, 1000L, 4),
                createRun(RunStatus.COMPLETED, 2000L, 5),
                createRun(RunStatus.FAILED, 500L, null),
                createRun(RunStatus.CANCELLED, null, null)
        );
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

        Map<String, Object> stats = learningService.getWorkflowStats("wf-1");

        assertThat(stats.get("totalRuns")).isEqualTo(4);
        assertThat(stats.get("completed")).isEqualTo(2L);
        assertThat(stats.get("failed")).isEqualTo(1L);
        assertThat(stats.get("cancelled")).isEqualTo(1L);
        assertThat((double) stats.get("successRate")).isCloseTo(0.5, within(0.01));
        assertThat((double) stats.get("avgDurationMs")).isCloseTo(1166.67, within(1.0));
        assertThat(stats.get("minDurationMs")).isEqualTo(500L);
        assertThat(stats.get("maxDurationMs")).isEqualTo(2000L);
        assertThat((double) stats.get("avgRating")).isCloseTo(4.5, within(0.01));
        assertThat(stats.get("ratedRuns")).isEqualTo(2L);
    }

    // --- getStepAnalytics ---

    @Test
    void getStepAnalytics_groupsByStepIndex() {
        StepResult step0a = createStep(0, "npm test", StepStatus.COMPLETED, 1000L);
        StepResult step0b = createStep(0, "npm test", StepStatus.FAILED, 1500L);
        StepResult step1a = createStep(1, "npm build", StepStatus.COMPLETED, 2000L);

        WorkflowRun run1 = createRunWithSteps(List.of(step0a, step1a));
        WorkflowRun run2 = createRunWithSteps(List.of(step0b));

        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1"))
                .thenReturn(List.of(run1, run2));

        List<Map<String, Object>> analytics = learningService.getStepAnalytics("wf-1");

        assertThat(analytics).hasSize(2);

        // Step 0
        Map<String, Object> step0Stats = analytics.get(0);
        assertThat(step0Stats.get("stepIndex")).isEqualTo(0);
        assertThat(step0Stats.get("totalExecutions")).isEqualTo(2L);
        assertThat(step0Stats.get("failures")).isEqualTo(1L);
        assertThat((double) step0Stats.get("failureRate")).isCloseTo(0.5, within(0.01));

        // Step 1
        Map<String, Object> step1Stats = analytics.get(1);
        assertThat(step1Stats.get("stepIndex")).isEqualTo(1);
        assertThat(step1Stats.get("totalExecutions")).isEqualTo(1L);
    }

    // --- getOptimizationSuggestions ---

    @Test
    void getOptimizationSuggestions_noRuns_returnsEmpty() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions("wf-1");

        assertThat(suggestions).isEmpty();
    }

    @Test
    void getOptimizationSuggestions_lowSuccessRate_suggestsReview() {
        List<WorkflowRun> runs = List.of(
                createRun(RunStatus.FAILED, 1000L, null),
                createRun(RunStatus.FAILED, 1000L, null),
                createRun(RunStatus.COMPLETED, 1000L, null)
        );
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

        List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions("wf-1");

        assertThat(suggestions).anySatisfy(s ->
                assertThat(s.get("type")).isEqualTo("LOW_SUCCESS_RATE")
        );
    }

    @Test
    void getOptimizationSuggestions_highVariance_suggestsInvestigation() {
        // Min 1s, max >5s, with 5+ runs
        List<WorkflowRun> runs = List.of(
                createRun(RunStatus.COMPLETED, 1000L, null),
                createRun(RunStatus.COMPLETED, 6000L, null),
                createRun(RunStatus.COMPLETED, 1000L, null),
                createRun(RunStatus.COMPLETED, 6000L, null),
                createRun(RunStatus.COMPLETED, 1000L, null)
        );
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

        List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions("wf-1");

        assertThat(suggestions).anySatisfy(s ->
                assertThat(s.get("type")).isEqualTo("HIGH_VARIANCE")
        );
    }

    // --- getSimilarWorkflows ---

    @Test
    void getSimilarWorkflows_findsSimilarByBlockTypes() {
        Workflow other = Workflow.builder()
                .id("wf-2")
                .name("Similar Workflow")
                .blocks(List.of(
                        BlockDefinition.builder().type("simple").build(),
                        BlockDefinition.builder().type("retry").build(),
                        BlockDefinition.builder().type("simple").build()
                ))
                .build();

        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.findAll()).thenReturn(List.of(testWorkflow, other));

        List<Map<String, Object>> similar = learningService.getSimilarWorkflows("wf-1");

        assertThat(similar).hasSize(1);
        assertThat(similar.get(0).get("workflowId")).isEqualTo("wf-2");
        assertThat(similar.get(0).get("similarity")).isEqualTo(100L);
    }

    @Test
    void getSimilarWorkflows_excludesDissimilar() {
        Workflow dissimilar = Workflow.builder()
                .id("wf-3")
                .name("Dissimilar")
                .blocks(List.of(
                        BlockDefinition.builder().type("foreach").build(),
                        BlockDefinition.builder().type("while").build()
                ))
                .build();

        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.findAll()).thenReturn(List.of(testWorkflow, dissimilar));

        List<Map<String, Object>> similar = learningService.getSimilarWorkflows("wf-1");

        assertThat(similar).isEmpty();
    }

    @Test
    void getSimilarWorkflows_nonExistentWorkflow_throwsException() {
        when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learningService.getSimilarWorkflows("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- rateRun ---

    @Test
    void rateRun_validRating_savesRating() {
        WorkflowRun run = createRun(RunStatus.COMPLETED, 1000L, null);
        run.setId("run-1");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenReturn(run);

        learningService.rateRun("run-1", 4);

        assertThat(run.getUserRating()).isEqualTo(4);
        verify(runRepository).save(run);
    }

    @Test
    void rateRun_invalidRating_throwsException() {
        assertThatThrownBy(() -> learningService.rateRun("run-1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");

        assertThatThrownBy(() -> learningService.rateRun("run-1", 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rateRun_nonExistentRun_throwsException() {
        when(runRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learningService.rateRun("unknown", 3))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- getDashboard ---

    @Test
    void getDashboard_computesOverview() {
        when(workflowRepository.count()).thenReturn(2L);
        when(runRepository.count()).thenReturn(3L);
        when(runRepository.countByStatus(RunStatus.COMPLETED)).thenReturn(2L);
        when(runRepository.countByStatus(RunStatus.FAILED)).thenReturn(1L);
        when(runRepository.countByStartedAtAfter(any(Instant.class))).thenReturn(0L);
        when(runRepository.findByStartedAtAfterOrderByStartedAtDesc(any(Instant.class))).thenReturn(List.of());
        when(runRepository.findTopWorkflows()).thenReturn(List.of());
        when(runRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());

        DashboardResponse dashboard = learningService.getDashboard();

        assertThat(dashboard.totalWorkflows()).isEqualTo(2L);
        assertThat(dashboard.totalRuns()).isEqualTo(3L);
        assertThat(dashboard.runsToday()).isEqualTo(0L);
        assertThat(dashboard.successfulRuns()).isEqualTo(2L);
        assertThat(dashboard.failedRuns()).isEqualTo(1L);
        assertThat(dashboard.successRate()).isCloseTo(0.667, within(0.01));
    }

    // --- getAgenticStrategyHistory ---

    @Test
    void getAgenticStrategyHistory_noAgenticSteps_returnsEmpty() {
        WorkflowRun run = createRunWithSteps(List.of(
                createStep(0, "npm test", StepStatus.COMPLETED, 1000L)
        ));
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        List<Map<String, Object>> history = learningService.getAgenticStrategyHistory("wf-1");

        assertThat(history).isEmpty();
    }

    @Test
    void getAgenticStrategyHistory_withAgenticSteps_extractsData() {
        StepResult agenticStep = StepResult.builder()
                .stepIndex(0)
                .input("[Agentic] Fix tests")
                .status(StepStatus.COMPLETED)
                .durationMs(5000L)
                .startedAt(java.time.Instant.now())
                .metadata(Map.of(
                        "blockType", "agentic",
                        "goal", "Fix all tests",
                        "totalIterations", 3,
                        "goalAchieved", true,
                        "iterations", List.of(
                                Map.of("action", "Run tests", "exitCode", 1),
                                Map.of("action", "Fix code", "exitCode", 0),
                                Map.of("action", "Run tests again", "exitCode", 0)
                        )
                ))
                .build();

        WorkflowRun run = createRunWithSteps(List.of(agenticStep));
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        List<Map<String, Object>> history = learningService.getAgenticStrategyHistory("wf-1");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("goal")).isEqualTo("Fix all tests");
        assertThat(history.get(0).get("totalIterations")).isEqualTo(3);
        assertThat(history.get(0).get("goalAchieved")).isEqualTo(true);
        assertThat(history.get(0).get("strategy").toString()).contains("Run tests");
    }

    // --- getAgenticAnalytics ---

    @Test
    void getAgenticAnalytics_noHistory_returnsZero() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> analytics = learningService.getAgenticAnalytics("wf-1");

        assertThat(analytics.get("totalAgenticRuns")).isEqualTo(0);
    }

    @Test
    void getAgenticAnalytics_withHistory_computesStats() {
        StepResult step1 = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).durationMs(5000L)
                .startedAt(java.time.Instant.now())
                .metadata(Map.of("blockType", "agentic", "goal", "Goal 1",
                        "totalIterations", 2, "goalAchieved", true, "iterations", List.of()))
                .build();
        StepResult step2 = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).durationMs(3000L)
                .startedAt(java.time.Instant.now().minusSeconds(60))
                .metadata(Map.of("blockType", "agentic", "goal", "Goal 2",
                        "totalIterations", 5, "goalAchieved", false, "iterations", List.of()))
                .build();

        WorkflowRun run = createRunWithSteps(List.of(step1, step2));
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        Map<String, Object> analytics = learningService.getAgenticAnalytics("wf-1");

        assertThat(analytics.get("totalAgenticRuns")).isEqualTo(2L);
        assertThat(analytics.get("achievedGoals")).isEqualTo(1L);
        assertThat((double) analytics.get("agenticSuccessRate")).isCloseTo(0.5, within(0.01));
        assertThat((double) analytics.get("avgIterationsToGoal")).isCloseTo(3.5, within(0.01));
    }

    // --- Helper methods ---

    private WorkflowRun createRun(RunStatus status, Long durationMs, Integer rating) {
        return WorkflowRun.builder()
                .status(status)
                .durationMs(durationMs)
                .userRating(rating)
                .steps(new ArrayList<>())
                .build();
    }

    private WorkflowRun createRunForWorkflow(Workflow wf, RunStatus status, Long durationMs) {
        return WorkflowRun.builder()
                .workflow(wf)
                .status(status)
                .durationMs(durationMs)
                .steps(new ArrayList<>())
                .build();
    }

    private WorkflowRun createRunWithSteps(List<StepResult> steps) {
        WorkflowRun run = WorkflowRun.builder()
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(steps))
                .build();
        return run;
    }

    private StepResult createStep(int index, String input, StepStatus status, Long durationMs) {
        return StepResult.builder()
                .stepIndex(index)
                .input(input)
                .status(status)
                .durationMs(durationMs)
                .build();
    }
}
