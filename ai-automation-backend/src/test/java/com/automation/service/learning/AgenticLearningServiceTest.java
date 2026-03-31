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

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticLearningServiceTest {

    @Mock private WorkflowRunRepository runRepository;

    private AgenticLearningService service;

    @BeforeEach
    void setUp() {
        service = new AgenticLearningService(runRepository);
    }

    @Test
    void getAgenticStrategyHistory_noRuns_emptyList() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        List<Map<String, Object>> result = service.getAgenticStrategyHistory("wf-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getAgenticStrategyHistory_withAgenticSteps_returnsHistory() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("blockType", "agentic");
        meta.put("goal", "fix bug");
        meta.put("totalIterations", 3);
        meta.put("goalAchieved", true);
        meta.put("iterations", List.of(
                Map.of("action", "read file"),
                Map.of("action", "edit file")));

        StepResult step = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).durationMs(5000L)
                .startedAt(Instant.now()).metadata(meta).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        List<Map<String, Object>> result = service.getAgenticStrategyHistory("wf-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("goal")).isEqualTo("fix bug");
        assertThat(result.get(0).get("goalAchieved")).isEqualTo(true);
        assertThat((String) result.get(0).get("strategy")).contains("read file -> edit file");
    }

    @Test
    void getAgenticAnalytics_noHistory_returnsZero() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> result = service.getAgenticAnalytics("wf-1");

        assertThat(result.get("totalAgenticRuns")).isEqualTo(0);
    }

    @Test
    void getAgenticAnalytics_withHistory_calculatesSuccessRate() {
        Map<String, Object> meta1 = Map.of("blockType", "agentic", "goal", "g1",
                "totalIterations", 2, "goalAchieved", true, "iterations", List.of());
        Map<String, Object> meta2 = Map.of("blockType", "agentic", "goal", "g2",
                "totalIterations", 5, "goalAchieved", false, "iterations", List.of());

        StepResult s1 = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .startedAt(Instant.now()).metadata(meta1).build();
        StepResult s2 = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .startedAt(Instant.now().minusSeconds(10)).metadata(meta2).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(s1, s2))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        Map<String, Object> result = service.getAgenticAnalytics("wf-1");

        assertThat((long) result.get("totalAgenticRuns")).isEqualTo(2);
        assertThat((long) result.get("achievedGoals")).isEqualTo(1);
        assertThat((double) result.get("agenticSuccessRate")).isEqualTo(0.5);
    }

    @Test
    void getToolPatterns_noRuns_emptyResult() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        Map<String, Object> result = service.getToolPatterns("wf-1");

        assertThat(result.get("totalAgenticRuns")).isEqualTo(0);
    }

    @Test
    void getToolPatterns_withTools_countsAndFindsPatterns() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("blockType", "agentic");
        meta.put("toolNames", List.of("read", "edit", "read"));
        meta.put("costUsd", 0.1);

        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .metadata(meta).build();
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));

        Map<String, Object> result = service.getToolPatterns("wf-1");

        assertThat(result.get("totalAgenticRuns")).isEqualTo(1);
        assertThat((List<?>) result.get("topTools")).isNotEmpty();
    }

    @Test
    void findCommonPatterns_pairsAndTriples_found() {
        List<List<String>> sequences = List.of(
                List.of("A", "B", "C"),
                List.of("A", "B", "C"),
                List.of("X", "Y"));

        List<Map<String, Object>> patterns = service.findCommonPatterns(sequences);

        assertThat(patterns).isNotEmpty();
        // A -> B appears twice, B -> C appears twice, A -> B -> C appears twice
        assertThat(patterns.stream().map(p -> p.get("pattern")))
                .contains("A -> B", "B -> C", "A -> B -> C");
    }

    @Test
    void extractToolsFromMeta_withToolNames_counted() {
        Map<String, Object> iterMap = Map.of(
                "actMeta", Map.of("toolNames", List.of("read", "write")));
        Map<String, Integer> counts = new LinkedHashMap<>();

        service.extractToolsFromMeta(iterMap, "actMeta", counts);

        assertThat(counts).containsEntry("read", 1).containsEntry("write", 1);
    }
}
