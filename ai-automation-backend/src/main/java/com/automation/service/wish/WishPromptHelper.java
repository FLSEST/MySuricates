package com.automation.service.wish;

import com.automation.entity.*;
import com.automation.repository.SkillRepository;
import com.automation.service.learning.LearningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared helper methods for wish prompt builders.
 * Extracted from WishPromptBuilder — keyword scoring, block pipeline formatting,
 * execution intelligence, and model inference.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WishPromptHelper {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "will", "have", "been",
            "are", "was", "were", "not", "but", "all", "can", "had", "her", "his",
            "one", "our", "out", "you", "its", "also", "into", "just", "than", "them",
            "then", "what", "when", "who", "how", "about", "which", "would", "there",
            "their", "should", "could", "does", "each", "more", "some", "such", "only",
            "der", "die", "das", "und", "ein", "eine", "ist", "ich", "sie", "den",
            "auf", "mit", "dem", "nicht", "sich", "des", "von", "aus", "hat", "noch",
            "nach", "bei", "wie", "nur", "über", "aber", "vor", "auch", "dann", "soll",
            "für", "oder", "wird", "alle", "mein", "dein", "sein", "ihr", "wir"
    );

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,;:.!?()\\[\\]{}\"/]+");

    private static final Pattern ANALYSIS_PATTERN = Pattern.compile(
            "(?i)(analy|audit|review|security|vulnerabilit|architect|assess|inspect|evaluat|quality|scorecard|coverage|debt|radar)",
            Pattern.CASE_INSENSITIVE);

    private final SkillRepository skillRepository;
    private final LearningService learningService;

    public String formatBlockPipeline(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) return "[empty]";
        return blocks.stream().map(b -> {
            String type = b.getType() != null ? b.getType() : "?";
            Map<String, Object> cfg = b.getConfig();
            String label = "";
            if (cfg != null) {
                if (cfg.containsKey("command")) {
                    label = WishStringUtil.truncate(String.valueOf(cfg.get("command")), 40);
                } else if (cfg.containsKey("goal")) {
                    String goal = WishStringUtil.truncate(String.valueOf(cfg.get("goal")), 30);
                    Object maxIter = cfg.get("max_iterations");
                    label = goal + (maxIter != null ? " (" + maxIter + " iter)" : "");
                }
            }
            return label.isEmpty() ? "[" + type + "]" : "[" + type + ": " + label + "]";
        }).collect(Collectors.joining(" → "));
    }

    public Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(WORD_SPLIT.split(text.toLowerCase(Locale.ROOT)))
                .filter(w -> w.length() >= 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    public int scoreRelevance(Set<String> keywords, String name, String description, List<BlockDefinition> blocks) {
        if (keywords.isEmpty()) return 0;
        int score = 0;
        String nameLower = name != null ? name.toLowerCase(Locale.ROOT) : "";
        String descLower = description != null ? description.toLowerCase(Locale.ROOT) : "";

        for (String kw : keywords) {
            if (nameLower.contains(kw)) score += 3;
            if (descLower.contains(kw)) score += 2;
        }

        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                if (block.getConfig() == null) continue;
                String configStr = block.getConfig().values().stream()
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.joining(" "))
                        .toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (configStr.contains(kw)) {
                        score += 1;
                        break;
                    }
                }
            }
        }
        return score;
    }

    /** CLI alias — always the newest Opus; analysis work needs its precision (Sonnet hallucinates findings). */
    static final String ANALYSIS_MODEL = "opus";

    public String inferModelFromBlocks(List<BlockDefinition> blocks, String description) {
        if (description != null && ANALYSIS_PATTERN.matcher(description).find()) {
            return ANALYSIS_MODEL;
        }
        for (BlockDefinition block : blocks) {
            if ("agentic".equals(block.getType()) && block.getConfig() != null) {
                String goal = (String) block.getConfig().getOrDefault("goal", "");
                if (ANALYSIS_PATTERN.matcher(goal).find()) {
                    return ANALYSIS_MODEL;
                }
            }
        }
        return null;
    }

    public void appendAvailableSkills(StringBuilder sb) {
        List<Skill> skills = skillRepository.findAll();
        if (!skills.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (Skill skill : skills) {
                sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * @param projectWorkflows the workflows of the target project as the caller already read them
     *                         (empty without a project) - do not load them a second time here (TODO 73036bbd)
     */
    @SuppressWarnings("unchecked")
    public String buildExecutionIntelligence(List<Workflow> projectWorkflows) {
        StringBuilder sb = new StringBuilder();
        try {
            List<String> performanceLines = new ArrayList<>();
            double totalCost = 0;
            long totalRuns = 0;
            Set<String> allModels = new LinkedHashSet<>();
            List<String> pitfalls = new ArrayList<>();

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> stats = learningService.getWorkflowStats(wf.getId());
                int runs = ((Number) stats.getOrDefault("totalRuns", 0)).intValue();
                if (runs < 2) continue;

                double successRate = ((Number) stats.getOrDefault("successRate", 0.0)).doubleValue();
                double avgDuration = ((Number) stats.getOrDefault("avgDurationMs", 0.0)).doubleValue();
                totalRuns += runs;

                Map<String, Object> insights = learningService.getExecutionInsights(wf.getId());
                double cost = ((Number) insights.getOrDefault("avgCostPerRunUsd", 0.0)).doubleValue();
                totalCost += ((Number) insights.getOrDefault("totalCostUsd", 0.0)).doubleValue();
                Object models = insights.get("modelsUsed");
                if (models instanceof List<?> modelList) {
                    modelList.forEach(m -> allModels.add(String.valueOf(m)));
                }

                performanceLines.add(String.format(
                        "- **%s** (%d runs): %.0f%% success, avg %.0fs, $%.3f/run",
                        wf.getName(), runs, successRate * 100, avgDuration / 1000.0, cost));

                List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions(wf.getId());
                for (Map<String, Object> sug : suggestions) {
                    String severity = (String) sug.getOrDefault("severity", "");
                    if ("HIGH".equals(severity)) {
                        pitfalls.add("- " + sug.getOrDefault("message", ""));
                    }
                }
            }

            if (performanceLines.isEmpty() && projectWorkflows.size() < 2) {
                return "";
            }

            sb.append("## Execution Intelligence (from past runs)\n\n");

            if (!performanceLines.isEmpty()) {
                sb.append("### Workflow Performance\n\n");
                performanceLines.stream().limit(10).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            if (!pitfalls.isEmpty()) {
                sb.append("### Common Issues to Avoid\n\n");
                pitfalls.stream().distinct().limit(5).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            int agenticRunsTotal = 0;
            int goalsAchieved = 0;
            double iterationsSum = 0;
            int iterationsCount = 0;

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> agenticAnalytics = learningService.getAgenticAnalytics(wf.getId());
                int agRuns = ((Number) agenticAnalytics.getOrDefault("totalAgenticRuns", 0)).intValue();
                if (agRuns == 0) continue;
                agenticRunsTotal += agRuns;
                goalsAchieved += ((Number) agenticAnalytics.getOrDefault("achievedGoals", 0)).intValue();
                double avgIter = ((Number) agenticAnalytics.getOrDefault("avgIterationsToGoal", 0.0)).doubleValue();
                if (avgIter > 0) {
                    iterationsSum += avgIter * agRuns;
                    iterationsCount += agRuns;
                }
            }

            if (agenticRunsTotal > 0) {
                double agenticSuccessRate = (double) goalsAchieved / agenticRunsTotal;
                double avgIter = iterationsCount > 0 ? iterationsSum / iterationsCount : 0;
                sb.append("### Agentic Block Insights\n\n");
                sb.append(String.format("- %d agentic runs total, %.0f%% goal achieved\n",
                        agenticRunsTotal, agenticSuccessRate * 100));
                if (avgIter > 0) {
                    sb.append(String.format("- Avg iterations to success: %.1f\n", avgIter));
                }
                sb.append("\n");
            }

            if (totalRuns > 0) {
                sb.append("### Cost Profile\n\n");
                sb.append(String.format("- Total project cost (recent): $%.2f across %d runs\n", totalCost, totalRuns));
                if (!allModels.isEmpty()) {
                    sb.append("- Models used: ").append(String.join(", ", allModels)).append("\n");
                }
                sb.append("\n");
            }

        } catch (Exception e) {
            log.debug("Could not build execution intelligence: {}", e.getMessage());
        }
        return sb.toString();
    }
}
