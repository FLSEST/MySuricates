package com.automation.service.learning;

import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.automation.service.learning.MetadataExtractor.extractDouble;

@Service
@RequiredArgsConstructor
public class AgenticLearningService {

    /** Run window of {@link #getToolPatterns}: runs started within this many days … */
    static final int TOOL_PATTERN_WINDOW_DAYS = 90;
    /** … and of those at most this many, newest first. */
    static final int TOOL_PATTERN_MAX_RUNS = 200;

    private final WorkflowRunRepository runRepository;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAgenticStrategyHistory(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        return runs.stream()
                .flatMap(r -> r.getSteps().stream())
                .filter(s -> s.getMetadata() != null
                        && "agentic".equals(s.getMetadata().get("blockType")))
                .sorted(Comparator.comparing(StepResult::getStartedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(s -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    Map<String, Object> meta = s.getMetadata();
                    entry.put("goal", meta.getOrDefault("goal", "unknown"));
                    entry.put("totalIterations", meta.getOrDefault("totalIterations", 0));
                    entry.put("goalAchieved", meta.getOrDefault("goalAchieved", false));
                    entry.put("outcome", s.getStatus().name());
                    entry.put("durationMs", s.getDurationMs());

                    List<Map<String, Object>> iterations = (List<Map<String, Object>>)
                            meta.getOrDefault("iterations", List.of());
                    List<String> strategies = iterations.stream()
                            .map(i -> (String) i.getOrDefault("action", ""))
                            .filter(a -> !a.isEmpty())
                            .toList();
                    entry.put("strategy", String.join(" -> ", strategies));

                    return entry;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgenticAnalytics(String workflowId) {
        List<Map<String, Object>> history = getAgenticStrategyHistory(workflowId);
        if (history.isEmpty()) {
            return Map.of("totalAgenticRuns", 0);
        }

        long total = history.size();
        long achieved = history.stream()
                .filter(h -> Boolean.TRUE.equals(h.get("goalAchieved")))
                .count();

        OptionalDouble avgIterations = history.stream()
                .mapToInt(h -> ((Number) h.getOrDefault("totalIterations", 0)).intValue())
                .average();

        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("totalAgenticRuns", total);
        analytics.put("achievedGoals", achieved);
        analytics.put("agenticSuccessRate", (double) achieved / total);
        analytics.put("avgIterationsToGoal", avgIterations.orElse(0));

        return analytics;
    }

    /**
     * Tool counts and recurring tool sequences of a workflow's steps, plus the number and cost of its
     * agentic steps.
     *
     * <p>Reads only the step metadata of the newest {@value #TOOL_PATTERN_MAX_RUNS} runs of the last
     * {@value #TOOL_PATTERN_WINDOW_DAYS} days (TODO 47c1cbe4). It runs for every workflow of a project
     * on each wish decomposition ({@code WorkflowStatsService#getOptimizationSuggestions}); loading
     * every run with its step entities, output text included, held the single database connection
     * for as long as the history was big.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getToolPatterns(String workflowId) {
        ToolTally tally = new ToolTally();
        recentStepMetadata(workflowId).forEach(tally::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflowId);
        result.put("totalAgenticRuns", tally.agenticSteps);
        result.put("totalCostUsd", tally.totalCost);
        result.put("patterns", findCommonPatterns(tally.toolSequences));
        result.put("topTools", topTools(tally.toolCounts));
        return result;
    }

    /** Step metadata of the run window, newest run first, steps in order; steps without metadata are left out. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recentStepMetadata(String workflowId) {
        Instant since = Instant.now().minus(TOOL_PATTERN_WINDOW_DAYS, ChronoUnit.DAYS);
        List<String> runIds = runRepository.findRecentRunIds(workflowId, since,
                PageRequest.of(0, TOOL_PATTERN_MAX_RUNS));
        if (runIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> metadata = new ArrayList<>();
        for (Object[] row : runRepository.findStepMetadataByRunIds(runIds)) {
            if (row[1] instanceof Map<?, ?> stepMeta) {
                metadata.add((Map<String, Object>) stepMeta);
            }
        }
        return metadata;
    }

    private static List<Map<String, Object>> topTools(Map<String, Integer> toolCounts) {
        return toolCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }

    /** What {@link #getToolPatterns} counts, one step's metadata at a time. */
    private final class ToolTally {
        private final Map<String, Integer> toolCounts = new LinkedHashMap<>();
        private final List<List<String>> toolSequences = new ArrayList<>();
        private int agenticSteps;
        private double totalCost;

        void add(Map<String, Object> stepMeta) {
            addToolSequence(stepMeta.get("toolNames"));
            if ("agentic".equals(stepMeta.get("blockType"))) {
                addAgenticStep(stepMeta);
            }
        }

        private void addToolSequence(Object toolNames) {
            if (!(toolNames instanceof List<?> list)) {
                return;
            }
            List<String> names = list.stream().map(Object::toString).toList();
            names.forEach(name -> toolCounts.merge(name, 1, Integer::sum));
            if (!names.isEmpty()) {
                toolSequences.add(names);
            }
        }

        @SuppressWarnings("unchecked")
        private void addAgenticStep(Map<String, Object> stepMeta) {
            agenticSteps++;
            totalCost += extractDouble(stepMeta, "costUsd");
            if (!(stepMeta.get("iterations") instanceof List<?> iterations)) {
                return;
            }
            for (Object iteration : iterations) {
                if (iteration instanceof Map<?, ?> iterMap) {
                    extractToolsFromMeta((Map<String, Object>) iterMap, "actMeta", toolCounts);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void extractToolsFromMeta(Map<String, Object> iterMap, String metaKey,
                               Map<String, Integer> toolCounts) {
        Object meta = iterMap.get(metaKey);
        if (meta instanceof Map<?, ?> metaMap) {
            Object names = ((Map<String, Object>) metaMap).get("toolNames");
            if (names instanceof List<?> nameList) {
                for (Object name : nameList) {
                    toolCounts.merge(name.toString(), 1, Integer::sum);
                }
            }
        }
    }

    List<Map<String, Object>> findCommonPatterns(List<List<String>> sequences) {
        Map<String, Integer> patternCounts = new LinkedHashMap<>();

        for (List<String> seq : sequences) {
            for (int i = 0; i < seq.size() - 1; i++) {
                String pattern = seq.get(i) + " -> " + seq.get(i + 1);
                patternCounts.merge(pattern, 1, Integer::sum);
            }
            for (int i = 0; i < seq.size() - 2; i++) {
                String pattern = seq.get(i) + " -> " + seq.get(i + 1) + " -> " + seq.get(i + 2);
                patternCounts.merge(pattern, 1, Integer::sum);
            }
        }

        return patternCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pattern", e.getKey());
                    m.put("occurrences", e.getValue());
                    return m;
                })
                .toList();
    }
}
