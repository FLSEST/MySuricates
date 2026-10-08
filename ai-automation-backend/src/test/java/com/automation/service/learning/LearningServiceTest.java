package com.automation.service.learning;

import com.automation.dto.projection.DashboardRunRow;
import com.automation.dto.projection.RunOverviewCounts;
import com.automation.dto.response.DashboardResponse;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    // Sub-services tested directly
    @InjectMocks
    private WorkflowStatsService statsService;

    @InjectMocks
    private WorkflowSimilarityService similarityService;

    @InjectMocks
    private AgenticLearningService agenticService;

    @InjectMocks
    private ExecutionInsightsService insightsService;

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

    // --- WorkflowStatsService ---

    @Nested
    class WorkflowStatsTests {

        @Test
        void getWorkflowStats_noRuns_returnsZeroTotal() {
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

            Map<String, Object> stats = statsService.getWorkflowStats("wf-1");

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

            Map<String, Object> stats = statsService.getWorkflowStats("wf-1");

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

        @Test
        void getStepAnalytics_groupsByStepIndex() {
            StepResult step0a = createStep(0, "npm test", StepStatus.COMPLETED, 1000L);
            StepResult step0b = createStep(0, "npm test", StepStatus.FAILED, 1500L);
            StepResult step1a = createStep(1, "npm build", StepStatus.COMPLETED, 2000L);

            WorkflowRun run1 = createRunWithSteps(List.of(step0a, step1a));
            WorkflowRun run2 = createRunWithSteps(List.of(step0b));

            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1"))
                    .thenReturn(List.of(run1, run2));

            List<Map<String, Object>> analytics = statsService.getStepAnalytics("wf-1");

            assertThat(analytics).hasSize(2);

            Map<String, Object> step0Stats = analytics.get(0);
            assertThat(step0Stats.get("stepIndex")).isEqualTo(0);
            assertThat(step0Stats.get("totalExecutions")).isEqualTo(2L);
            assertThat(step0Stats.get("failures")).isEqualTo(1L);
            assertThat((double) step0Stats.get("failureRate")).isCloseTo(0.5, within(0.01));

            Map<String, Object> step1Stats = analytics.get(1);
            assertThat(step1Stats.get("stepIndex")).isEqualTo(1);
            assertThat(step1Stats.get("totalExecutions")).isEqualTo(1L);
        }

        @Test
        void getOptimizationSuggestions_noRuns_returnsEmpty() {
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", insightsService, agenticService);

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

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", insightsService, agenticService);

            assertThat(suggestions).anySatisfy(s ->
                    assertThat(s.get("type")).isEqualTo("LOW_SUCCESS_RATE")
            );
        }

        @Test
        void getWorkflowStats_allNullDurations_avgDurationIsZero() {
            List<WorkflowRun> runs = List.of(
                    createRun(RunStatus.COMPLETED, null, null),
                    createRun(RunStatus.COMPLETED, null, null)
            );
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

            Map<String, Object> stats = statsService.getWorkflowStats("wf-1");

            assertThat(((Number) stats.get("avgDurationMs")).doubleValue()).isEqualTo(0.0);
            assertThat(((Number) stats.get("minDurationMs")).longValue()).isEqualTo(0L);
            assertThat(((Number) stats.get("maxDurationMs")).longValue()).isEqualTo(0L);
        }

        @Test
        void getWorkflowStats_onlyOneRated_avgRatingIsExact() {
            List<WorkflowRun> runs = List.of(
                    createRun(RunStatus.COMPLETED, 1000L, 3),
                    createRun(RunStatus.COMPLETED, 2000L, null)
            );
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

            Map<String, Object> stats = statsService.getWorkflowStats("wf-1");

            assertThat((double) stats.get("avgRating")).isCloseTo(3.0, within(0.01));
            assertThat(stats.get("ratedRuns")).isEqualTo(1L);
        }

        @Test
        void getOptimizationSuggestions_stepFailureHotspot_detected() {
            StepResult failStep1 = createStep(0, "flaky-cmd", StepStatus.FAILED, 500L);
            StepResult failStep2 = createStep(0, "flaky-cmd", StepStatus.FAILED, 600L);
            StepResult okStep = createStep(0, "flaky-cmd", StepStatus.COMPLETED, 400L);

            List<WorkflowRun> runs = List.of(
                    createRunWithSteps(List.of(failStep1)),
                    createRunWithSteps(List.of(failStep2)),
                    createRunWithSteps(List.of(okStep))
            );
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", insightsService, agenticService);

            assertThat(suggestions).anySatisfy(s ->
                    assertThat(s.get("type")).isEqualTo("STEP_FAILURE_HOTSPOT")
            );
        }

        @Test
        void getOptimizationSuggestions_slowStep_detected() {
            // Step takes 40s avg, total workflow avg is 50s -> 80% of total (>50%)
            StepResult slowStep = createStep(0, "slow-cmd", StepStatus.COMPLETED, 40_000L);
            WorkflowRun run1 = createRunWithSteps(List.of(slowStep));
            run1.setDurationMs(50_000L);
            WorkflowRun run2 = createRunWithSteps(List.of(createStep(0, "slow-cmd", StepStatus.COMPLETED, 40_000L)));
            run2.setDurationMs(50_000L);
            WorkflowRun run3 = createRunWithSteps(List.of(createStep(0, "slow-cmd", StepStatus.COMPLETED, 40_000L)));
            run3.setDurationMs(50_000L);

            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1"))
                    .thenReturn(List.of(run1, run2, run3));

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", insightsService, agenticService);

            assertThat(suggestions).anySatisfy(s ->
                    assertThat(s.get("type")).isEqualTo("SLOW_STEP")
            );
        }

        @Test
        void getOptimizationSuggestions_insightsThrow_gracefullyIgnored() {
            List<WorkflowRun> runs = List.of(
                    createRun(RunStatus.COMPLETED, 1000L, null),
                    createRun(RunStatus.COMPLETED, 1000L, null),
                    createRun(RunStatus.COMPLETED, 1000L, null)
            );
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

            // Should not throw even if insights/agentic fail
            ExecutionInsightsService failingInsights = mock(ExecutionInsightsService.class);
            when(failingInsights.getExecutionInsights(any())).thenThrow(new RuntimeException("DB error"));

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", failingInsights, agenticService);

            // Should still return without cost-based suggestions
            assertThat(suggestions).isNotNull();
        }

        @Test
        void getOptimizationSuggestions_highVariance_suggestsInvestigation() {
            List<WorkflowRun> runs = List.of(
                    createRun(RunStatus.COMPLETED, 1000L, null),
                    createRun(RunStatus.COMPLETED, 6000L, null),
                    createRun(RunStatus.COMPLETED, 1000L, null),
                    createRun(RunStatus.COMPLETED, 6000L, null),
                    createRun(RunStatus.COMPLETED, 1000L, null)
            );
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(runs);

            List<Map<String, Object>> suggestions = statsService.getOptimizationSuggestions("wf-1", insightsService, agenticService);

            assertThat(suggestions).anySatisfy(s ->
                    assertThat(s.get("type")).isEqualTo("HIGH_VARIANCE")
            );
        }
    }

    // --- WorkflowSimilarityService ---

    @Nested
    class SimilarityTests {

        @Test
        void getSimilarWorkflows_findsSimilarByBlockTypes() {
            Workflow wf = Workflow.builder()
                    .id("wf-1")
                    .name("Test Workflow")
                    .blockTypes("retry,simple")
                    .blocks(List.of(
                            BlockDefinition.builder().type("simple").build(),
                            BlockDefinition.builder().type("retry").build()
                    ))
                    .build();

            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(workflowRepository.findAllIdNameBlockTypes("wf-1"))
                    .thenReturn(objectArrayList(new Object[]{"wf-2", "Similar Workflow", "retry,simple"}));

            List<Map<String, Object>> similar = similarityService.getSimilarWorkflows("wf-1");

            assertThat(similar).hasSize(1);
            assertThat(similar.get(0).get("workflowId")).isEqualTo("wf-2");
            assertThat(similar.get(0).get("similarity")).isEqualTo(100L);
        }

        @Test
        void getSimilarWorkflows_excludesDissimilar() {
            Workflow wf = Workflow.builder()
                    .id("wf-1")
                    .name("Test Workflow")
                    .blockTypes("retry,simple")
                    .blocks(List.of(
                            BlockDefinition.builder().type("simple").build(),
                            BlockDefinition.builder().type("retry").build()
                    ))
                    .build();

            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(workflowRepository.findAllIdNameBlockTypes("wf-1"))
                    .thenReturn(objectArrayList(new Object[]{"wf-3", "Dissimilar", "foreach,while"}));

            List<Map<String, Object>> similar = similarityService.getSimilarWorkflows("wf-1");

            assertThat(similar).isEmpty();
        }

        @Test
        void getSimilarWorkflows_nonExistentWorkflow_throwsException() {
            when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> similarityService.getSimilarWorkflows("unknown"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getSimilarWorkflows_cachesResults() {
            Workflow wf = Workflow.builder()
                    .id("wf-1")
                    .name("Test Workflow")
                    .blockTypes("retry,simple")
                    .blocks(List.of(
                            BlockDefinition.builder().type("simple").build(),
                            BlockDefinition.builder().type("retry").build()
                    ))
                    .build();

            when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(wf));
            when(workflowRepository.findAllIdNameBlockTypes("wf-1"))
                    .thenReturn(objectArrayList(new Object[]{"wf-2", "Similar", "retry,simple"}));

            similarityService.getSimilarWorkflows("wf-1");
            similarityService.getSimilarWorkflows("wf-1");

            verify(workflowRepository, times(1)).findAllIdNameBlockTypes("wf-1");
        }

        @Test
        void getSimilarWorkflows_fallsBackToBlocks_whenBlockTypesEmpty() {
            Workflow wf = Workflow.builder()
                    .id("wf-cache-test")
                    .name("No Block Types")
                    .blockTypes("")
                    .blocks(List.of(
                            BlockDefinition.builder().type("simple").build(),
                            BlockDefinition.builder().type("retry").build()
                    ))
                    .build();

            when(workflowRepository.findById("wf-cache-test")).thenReturn(Optional.of(wf));
            when(workflowRepository.findAllIdNameBlockTypes("wf-cache-test"))
                    .thenReturn(objectArrayList(new Object[]{"wf-5", "Other", "retry,simple"}));

            List<Map<String, Object>> similar = similarityService.getSimilarWorkflows("wf-cache-test");

            assertThat(similar).hasSize(1);
            assertThat(similar.get(0).get("workflowId")).isEqualTo("wf-5");
        }
    }

    // --- ExecutionInsightsService ---

    @Nested
    class InsightsTests {

        @Test
        void rateRun_validRating_savesRating() {
            WorkflowRun run = createRun(RunStatus.COMPLETED, 1000L, null);
            run.setId("run-1");
            when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
            when(runRepository.save(any())).thenReturn(run);

            insightsService.rateRun("run-1", 4);

            assertThat(run.getUserRating()).isEqualTo(4);
            verify(runRepository).save(run);
        }

        @Test
        void rateRun_invalidRating_throwsException() {
            assertThatThrownBy(() -> insightsService.rateRun("run-1", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 1 and 5");

            assertThatThrownBy(() -> insightsService.rateRun("run-1", 6))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rateRun_nonExistentRun_throwsException() {
            when(runRepository.findById("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> insightsService.rateRun("unknown", 3))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // --- LearningService (facade) ---

    @Nested
    class FacadeTests {

        @Test
        void getDashboard_computesOverview() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);

            when(workflowRepository.count()).thenReturn(2L);
            when(runRepository.countOverview(any(Instant.class))).thenReturn(new RunOverviewCounts(3, 2, 1, 0));
            when(runRepository.findDashboardRows(any(Instant.class), isNull())).thenReturn(List.of());
            when(runRepository.findTopWorkflows()).thenReturn(List.of());

            DashboardResponse dashboard = learningService.getDashboard(30, null);

            assertThat(dashboard.totalWorkflows()).isEqualTo(2L);
            assertThat(dashboard.totalRuns()).isEqualTo(3L);
            assertThat(dashboard.runsToday()).isEqualTo(0L);
            assertThat(dashboard.successfulRuns()).isEqualTo(2L);
            assertThat(dashboard.failedRuns()).isEqualTo(1L);
            assertThat(dashboard.successRate()).isCloseTo(0.667, within(0.01));
        }

        /** Project filter: the overview figures come from the project-scoped queries only (TODO 4e1c9ad4). */
        @Test
        void getDashboard_withProject_usesProjectScopedOverview() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);

            when(workflowRepository.countByProjectId("p-1")).thenReturn(1L);
            when(runRepository.countOverviewByProject(eq("p-1"), any(Instant.class)))
                    .thenReturn(new RunOverviewCounts(4, 3, 1, 3));
            when(runRepository.findDashboardRows(any(Instant.class), eq("p-1"))).thenReturn(List.of());
            when(runRepository.findTopWorkflowsByProject("p-1"))
                    .thenReturn(objectArrayList(new Object[]{"wf-1", "Mine", 4L}));

            DashboardResponse dashboard = learningService.getDashboard(30, "p-1");

            assertThat(dashboard.totalWorkflows()).isEqualTo(1L);
            assertThat(dashboard.totalRuns()).isEqualTo(4L);
            assertThat(dashboard.successfulRuns()).isEqualTo(3L);
            assertThat(dashboard.failedRuns()).isEqualTo(1L);
            assertThat(dashboard.successRate()).isCloseTo(0.75, within(0.001));
            assertThat(dashboard.runsToday()).isEqualTo(3L);
            assertThat(dashboard.topWorkflows())
                    .extracting(DashboardResponse.TopWorkflowEntry::workflowId).containsExactly("wf-1");
            verify(workflowRepository, never()).count();
            verify(runRepository, never()).countOverview(any(Instant.class));
            verify(runRepository, never()).findTopWorkflows();
        }

        /**
         * The window comes as projected rows, filtered by project in the query (TODO 2ccb9e40): the
         * project goes to both window queries, and gate status, failure reason, trigger and the
         * recent-run line are read from the row's extracted values.
         */
        @Test
        void getDashboard_readsTheProjectedRowsAndPassesTheProjectToBothWindowQueries() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);
            DashboardRunRow failed = new DashboardRunRow("r1", RunStatus.FAILED, Instant.now(), null, 2000L,
                    null, null, "Nightly (old name)", "wf-1", "Nightly", "p-1", "Mine", "RED", "true", "x".repeat(120));
            DashboardRunRow green = new DashboardRunRow("r2", RunStatus.COMPLETED, Instant.now().minusSeconds(5),
                    null, 1000L, TriggerSource.SCHEDULE, null, null, "wf-1", "Nightly", "p-1", "Mine", "GREEN", null, null);
            when(workflowRepository.countByProjectId("p-1")).thenReturn(1L);
            when(runRepository.countOverviewByProject(eq("p-1"), any(Instant.class)))
                    .thenReturn(new RunOverviewCounts(2, 1, 1, 2));
            when(runRepository.findDashboardRows(any(Instant.class), eq("p-1"))).thenReturn(List.of(failed, green));
            when(runRepository.findTopWorkflowsByProject("p-1")).thenReturn(List.of());

            DashboardResponse dashboard = learningService.getDashboard(30, "p-1");

            verify(runRepository).findStepMetadataForDashboard(any(Instant.class), eq("p-1"));
            assertThat(dashboard.averageDurationMs()).isEqualTo(1500.0);
            assertThat(dashboard.gateStatusSummary()).extracting(DashboardResponse.GateStatusEntry::status)
                    .containsExactlyInAnyOrder("RED", "GREEN");
            assertThat(dashboard.failureReasons()).singleElement()
                    .satisfies(f -> assertThat(f.reason()).hasSize(103).endsWith("..."));
            assertThat(dashboard.triggerDistribution().todoTrigger()).isEqualTo(1);
            assertThat(dashboard.triggerDistribution().scheduled()).isEqualTo(1);
            assertThat(dashboard.costByWorkflow()).singleElement()
                    .satisfies(c -> assertThat(c.workflowName()).isEqualTo("Nightly"));
            assertThat(dashboard.recentRuns()).extracting(DashboardResponse.RecentRunEntry::workflowName)
                    .containsExactly("Nightly (old name)", "Nightly");
        }

        @Test
        void getDashboard_blankProjectMeansAllProjects() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);
            when(runRepository.countOverview(any(Instant.class))).thenReturn(new RunOverviewCounts(0, 0, 0, 0));

            learningService.getDashboard(30, "  ");

            verify(runRepository).findDashboardRows(any(Instant.class), isNull());
            verify(runRepository).findStepMetadataForDashboard(any(Instant.class), isNull());
        }

        @Test
        void getDashboard_integrationProvider_isExcludedFromAiProviderStats() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);

            // Run 1: integration step (Trello) followed by an AI step (Claude)
            // Run 2: integration-only run (Jira) — must NOT contribute a "jira" provider entry
            DashboardRunRow mixedRun = row("run-mixed", RunStatus.COMPLETED);
            DashboardRunRow integrationOnlyRun = row("run-int", RunStatus.COMPLETED);

            when(workflowRepository.count()).thenReturn(1L);
            when(runRepository.countOverview(any(Instant.class))).thenReturn(new RunOverviewCounts(2, 2, 0, 0));
            when(runRepository.findDashboardRows(any(Instant.class), isNull()))
                    .thenReturn(List.of(mixedRun, integrationOnlyRun));
            // Steps now come from a lightweight projection (runId, status, metadata), ordered by stepIndex.
            when(runRepository.findStepMetadataForDashboard(any(Instant.class), isNull())).thenReturn(objectArrayList(
                    new Object[]{"run-mixed", StepStatus.COMPLETED,
                            Map.of("provider", "trello", "executionMode", "integration", "blockType", "integration")},
                    new Object[]{"run-mixed", StepStatus.COMPLETED,
                            Map.of("provider", "CLAUDE", "model", "claude-sonnet-4-6", "blockType", "simple")},
                    new Object[]{"run-int", StepStatus.COMPLETED,
                            Map.of("provider", "jira", "executionMode", "integration", "blockType", "integration")}
            ));
            when(runRepository.findTopWorkflows()).thenReturn(List.of());

            DashboardResponse dashboard = learningService.getDashboard(30, null);

            // Only CLAUDE should appear — trello/jira are integration connectors, not AI providers
            assertThat(dashboard.providerStats())
                    .extracting(DashboardResponse.ProviderStats::provider)
                    .containsExactly("CLAUDE")
                    .doesNotContain("trello", "jira", "google-calendar");

            // Integrations should appear in their own bucket (per-step aggregation)
            assertThat(dashboard.integrationStats())
                    .extracting(DashboardResponse.IntegrationStats::provider)
                    .containsExactlyInAnyOrder("trello", "jira");
            DashboardResponse.IntegrationStats trello = dashboard.integrationStats().stream()
                    .filter(s -> "trello".equals(s.provider())).findFirst().orElseThrow();
            assertThat(trello.runCount()).isEqualTo(1L);
            assertThat(trello.successRate()).isEqualTo(1.0);
        }

        @Test
        void getDashboard_failedIntegrationStep_countsTowardIntegrationStats() {
            LearningService learningService = new LearningService(
                    runRepository, workflowRepository,
                    statsService, similarityService, agenticService, insightsService);

            DashboardRunRow failedRun = row("run-failed", RunStatus.FAILED);

            when(workflowRepository.count()).thenReturn(1L);
            when(runRepository.countOverview(any(Instant.class))).thenReturn(new RunOverviewCounts(1, 0, 1, 0));
            when(runRepository.findDashboardRows(any(Instant.class), isNull()))
                    .thenReturn(List.of(failedRun));
            // Failed integration step still carries executionMode=integration (set in the catch branch).
            when(runRepository.findStepMetadataForDashboard(any(Instant.class), isNull())).thenReturn(objectArrayList(
                    new Object[]{"run-failed", StepStatus.FAILED,
                            Map.of("provider", "trello", "action", "createCard",
                                    "executionMode", "integration", "error", "API rate limit")}
            ));
            when(runRepository.findTopWorkflows()).thenReturn(List.of());

            DashboardResponse dashboard = learningService.getDashboard(30, null);

            // Trello must NOT leak into providerStats even on failure
            assertThat(dashboard.providerStats())
                    .extracting(DashboardResponse.ProviderStats::provider)
                    .doesNotContain("trello");

            // Failure shows up in integrationStats with successRate=0
            assertThat(dashboard.integrationStats()).hasSize(1);
            DashboardResponse.IntegrationStats trello = dashboard.integrationStats().get(0);
            assertThat(trello.provider()).isEqualTo("trello");
            assertThat(trello.runCount()).isEqualTo(1L);
            assertThat(trello.successRate()).isEqualTo(0.0);
        }
    }

    // --- AgenticLearningService ---

    @Nested
    class AgenticTests {

        @Test
        void getAgenticStrategyHistory_noAgenticSteps_returnsEmpty() {
            WorkflowRun run = createRunWithSteps(List.of(
                    createStep(0, "npm test", StepStatus.COMPLETED, 1000L)
            ));
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

            List<Map<String, Object>> history = agenticService.getAgenticStrategyHistory("wf-1");

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

            List<Map<String, Object>> history = agenticService.getAgenticStrategyHistory("wf-1");

            assertThat(history).hasSize(1);
            assertThat(history.get(0).get("goal")).isEqualTo("Fix all tests");
            assertThat(history.get(0).get("totalIterations")).isEqualTo(3);
            assertThat(history.get(0).get("goalAchieved")).isEqualTo(true);
            assertThat(history.get(0).get("strategy").toString()).contains("Run tests");
        }

        @Test
        void getAgenticAnalytics_noHistory_returnsZero() {
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

            Map<String, Object> analytics = agenticService.getAgenticAnalytics("wf-1");

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

            Map<String, Object> analytics = agenticService.getAgenticAnalytics("wf-1");

            assertThat(analytics.get("totalAgenticRuns")).isEqualTo(2L);
            assertThat(analytics.get("achievedGoals")).isEqualTo(1L);
            assertThat((double) analytics.get("agenticSuccessRate")).isCloseTo(0.5, within(0.01));
            assertThat((double) analytics.get("avgIterationsToGoal")).isCloseTo(3.5, within(0.01));
        }
    }

    @Nested
    class AgenticToolPatternTests {

        @Test
        void getToolPatterns_noAgenticSteps_returnsEmptyPatterns() {
            WorkflowRun run = createRunWithSteps(List.of(
                    createStep(0, "npm test", StepStatus.COMPLETED, 1000L)
            ));
            stubToolPatternSteps(List.of(run));

            Map<String, Object> patterns = agenticService.getToolPatterns("wf-1");

            assertThat(patterns.get("totalAgenticRuns")).isEqualTo(0);
            assertThat((List<?>) patterns.get("topTools")).isEmpty();
        }

        @Test
        void getToolPatterns_countsToolsByName() {
            StepResult step = StepResult.builder()
                    .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of(
                            "toolNames", List.of("Read", "Edit", "Read"),
                            "blockType", "agentic", "costUsd", 0.01
                    ))
                    .build();

            WorkflowRun run = createRunWithSteps(List.of(step));
            stubToolPatternSteps(List.of(run));

            Map<String, Object> result = agenticService.getToolPatterns("wf-1");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topTools = (List<Map<String, Object>>) result.get("topTools");
            assertThat(topTools).isNotEmpty();
            // Read should have count 2, Edit count 1
            assertThat(topTools.get(0).get("name")).isEqualTo("Read");
            assertThat(topTools.get(0).get("count")).isEqualTo(2);
        }

        @Test
        void getToolPatterns_findsBigramPatterns() {
            // Same sequence [Read, Edit] appears twice -> should be detected
            StepResult step1 = StepResult.builder()
                    .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of("toolNames", List.of("Read", "Edit")))
                    .build();
            StepResult step2 = StepResult.builder()
                    .stepIndex(1).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of("toolNames", List.of("Read", "Edit")))
                    .build();

            WorkflowRun run = createRunWithSteps(List.of(step1, step2));
            stubToolPatternSteps(List.of(run));

            Map<String, Object> result = agenticService.getToolPatterns("wf-1");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
            assertThat(patterns).anySatisfy(p ->
                    assertThat(p.get("pattern")).isEqualTo("Read -> Edit")
            );
        }

        @Test
        void getToolPatterns_patternsFilteredByMinOccurrence2() {
            // Single occurrence should NOT appear in patterns
            StepResult step = StepResult.builder()
                    .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of("toolNames", List.of("Unique1", "Unique2")))
                    .build();

            WorkflowRun run = createRunWithSteps(List.of(step));
            stubToolPatternSteps(List.of(run));

            Map<String, Object> result = agenticService.getToolPatterns("wf-1");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
            assertThat(patterns).isEmpty();
        }

        @Test
        void getToolPatterns_accumulatesCostFromAgenticSteps() {
            StepResult step1 = StepResult.builder()
                    .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of("blockType", "agentic", "costUsd", 0.05))
                    .build();
            StepResult step2 = StepResult.builder()
                    .stepIndex(1).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(Map.of("blockType", "agentic", "costUsd", 0.03))
                    .build();

            WorkflowRun run = createRunWithSteps(List.of(step1, step2));
            stubToolPatternSteps(List.of(run));

            Map<String, Object> result = agenticService.getToolPatterns("wf-1");

            assertThat((double) result.get("totalCostUsd")).isCloseTo(0.08, within(0.001));
            assertThat(result.get("totalAgenticRuns")).isEqualTo(2);
        }
    }

    @Nested
    class AgenticStrategyHistoryExtendedTests {

        @Test
        void getAgenticStrategyHistory_moreThan10_limitsTo10() {
            List<StepResult> steps = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                steps.add(StepResult.builder()
                        .stepIndex(i).status(StepStatus.COMPLETED).durationMs(1000L)
                        .startedAt(java.time.Instant.now().minusSeconds(i * 60L))
                        .metadata(Map.of("blockType", "agentic", "goal", "Goal " + i,
                                "totalIterations", 1, "goalAchieved", true, "iterations", List.of()))
                        .build());
            }

            WorkflowRun run = createRunWithSteps(steps);
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

            List<Map<String, Object>> history = agenticService.getAgenticStrategyHistory("wf-1");

            assertThat(history).hasSize(10);
        }

        @Test
        void getAgenticStrategyHistory_nullMetadata_filtered() {
            StepResult step = StepResult.builder()
                    .stepIndex(0).status(StepStatus.COMPLETED).durationMs(1000L)
                    .metadata(null)
                    .build();

            WorkflowRun run = createRunWithSteps(List.of(step));
            when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

            List<Map<String, Object>> history = agenticService.getAgenticStrategyHistory("wf-1");

            assertThat(history).isEmpty();
        }
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

    /**
     * Stubs the lean queries of {@code getToolPatterns} (run ids of the window, then step metadata)
     * with the steps of {@code runs}, newest run first.
     */
    private void stubToolPatternSteps(List<WorkflowRun> runs) {
        List<String> runIds = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            String runId = "run-" + i;
            runIds.add(runId);
            for (StepResult step : runs.get(i).getSteps()) {
                rows.add(new Object[]{runId, step.getMetadata()});
            }
        }
        when(runRepository.findRecentRunIds(eq("wf-1"), any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(runIds);
        if (!runIds.isEmpty()) {
            when(runRepository.findStepMetadataByRunIds(runIds)).thenReturn(rows);
        }
    }

    private WorkflowRun createRunWithSteps(List<StepResult> steps) {
        return WorkflowRun.builder()
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(steps))
                .build();
    }

    private StepResult createStep(int index, String input, StepStatus status, Long durationMs) {
        return StepResult.builder()
                .stepIndex(index)
                .input(input)
                .status(status)
                .durationMs(durationMs)
                .build();
    }

    private static DashboardRunRow row(String id, RunStatus status) {
        return new DashboardRunRow(id, status, Instant.now(), null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static List<Object[]> objectArrayList(Object[]... rows) {
        return List.of(rows);
    }
}
