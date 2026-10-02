package com.automation.service.learning;

import com.automation.dto.projection.RunOverviewCounts;
import com.automation.dto.response.DashboardResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardModelStatsTest {
    @Test
    void groupsByAllThreeFieldsAndSumsOnlyMatchingCombinations() {
        var stats = dashboard(List.of(
                usage("sonnet", "CLAUDE", "claude-sonnet-20260101"),
                usage("sonnet", "CLAUDE", "claude-sonnet-20260201"),
                usage("sonnet", "PI", "claude-sonnet"),
                usage("claude-sonnet", "CLAUDE", "claude-sonnet"),
                usage("sonnet", "CLAUDE", "claude-other"))).modelStats();

        assertThat(stats).containsExactlyInAnyOrder(
                new DashboardResponse.ModelStats("claude-sonnet", "sonnet", "CLAUDE", 2, 300, 0.5),
                new DashboardResponse.ModelStats("claude-sonnet", "sonnet", "PI", 1, 150, 0.25),
                new DashboardResponse.ModelStats("claude-sonnet", "claude-sonnet", "CLAUDE", 1, 150, 0.25),
                new DashboardResponse.ModelStats("claude-other", "sonnet", "CLAUDE", 1, 150, 0.25));
        assertThat(stats.getFirst().totalTokens()).isEqualTo(300);
    }

    @Test
    void preservesSyntheticAndMissingFieldsWithoutInventingModelsOrCountingConnectors() {
        var stats = dashboard(List.of(
                Map.of("requestedModel", "opus", "provider", "CLAUDE", "model", "<synthetic>"),
                Map.of("requestedModel", "gpt-model", "provider", "CODEX"),
                Map.of("provider", "PI", "model", " ", "requestedModel", ""),
                Map.of("model", "legacy-model"),
                Map.of("provider", "jira", "executionMode", "integration", "model", "connector-model"),
                Map.of("blockType", "shell"))).modelStats();

        assertThat(stats).containsExactlyInAnyOrder(
                new DashboardResponse.ModelStats("<synthetic>", "opus", "CLAUDE", 1, 0, 0),
                new DashboardResponse.ModelStats(null, "gpt-model", "CODEX", 1, 0, 0),
                new DashboardResponse.ModelStats(null, null, "PI", 1, 0, 0),
                new DashboardResponse.ModelStats("legacy-model", null, null, 1, 0, 0));
    }

    private Map<String, Object> usage(String requested, String provider, String model) {
        return Map.of("requestedModel", requested, "provider", provider, "model", model,
                "inputTokens", 100, "outputTokens", 50, "costUsd", 0.25);
    }

    private DashboardResponse dashboard(List<Map<String, Object>> metadata) {
        var runs = mock(WorkflowRunRepository.class);
        var service = new LearningService(runs, mock(WorkflowRepository.class), null, null, null, null);
        when(runs.countOverview(any(Instant.class))).thenReturn(new RunOverviewCounts(1, 0, 1, 1));
        when(runs.findDashboardWindow(any(Instant.class))).thenReturn(List.of(WorkflowRun.builder()
                .id("run").status(RunStatus.FAILED).startedAt(Instant.now()).build()));
        when(runs.findStepMetadataForDashboard(any(Instant.class))).thenReturn(metadata.stream()
                .map(meta -> new Object[]{"run", StepStatus.FAILED, meta}).toList());
        return service.getDashboard(30, null);
    }
}
