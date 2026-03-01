package com.automation.service.learning;

import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.automation.service.learning.MetadataExtractor.extractDouble;

@Service
@RequiredArgsConstructor
public class AgenticLearningService {

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

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getToolPatterns(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        List<List<String>> toolSequences = new ArrayList<>();
        int totalAgenticRuns = 0;
        double totalCost = 0.0;

        for (WorkflowRun run : runs) {
            for (StepResult step : run.getSteps()) {
                if (step.getMetadata() == null) continue;

                Object toolNamesObj = step.getMetadata().get("toolNames");
                if (toolNamesObj instanceof List<?> toolNames) {
                    List<String> names = toolNames.stream().map(Object::toString).toList();
                    for (String name : names) {
                        toolCounts.merge(name, 1, Integer::sum);
                    }
                    if (!names.isEmpty()) {
                        toolSequences.add(names);
                    }
                }

                if ("agentic".equals(step.getMetadata().get("blockType"))) {
                    totalAgenticRuns++;
                    totalCost += extractDouble(step.getMetadata(), "costUsd");

                    Object iterations = step.getMetadata().get("iterations");
                    if (iterations instanceof List<?> iterList) {
                        for (Object iter : iterList) {
                            if (iter instanceof Map<?, ?> iterMap) {
                                extractToolsFromMeta((Map<String, Object>) iterMap, "actMeta", toolCounts);
                            }
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> patterns = findCommonPatterns(toolSequences);

        List<Map<String, Object>> topTools = toolCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflowId);
        result.put("totalAgenticRuns", totalAgenticRuns);
        result.put("totalCostUsd", totalCost);
        result.put("patterns", patterns);
        result.put("topTools", topTools);

        return result;
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
