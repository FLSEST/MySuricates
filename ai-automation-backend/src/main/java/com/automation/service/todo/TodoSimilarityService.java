package com.automation.service.todo;

import com.automation.config.TodoSimilarityConfig;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds semantically similar TODOs using a two-stage approach:
 * 1. Keyword-based Jaccard similarity (fast, no AI)
 * 2. AI-based semantic comparison for candidates (precise, uses configured default provider)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoSimilarityService {

    private final TodoRepository todoRepository;
    private final AiProviderRegistry aiProviderRegistry;
    private final TodoSimilarityConfig config;

    public enum Similarity { SAME, RELATED, DIFFERENT }

    public record SimilarTodo(Todo todo, double keywordScore, Similarity similarity) {}

    private static final Set<String> STOP_WORDS = Set.of(
            "aus", "in", "und", "nach", "von", "für", "zu", "um", "mit", "auf", "bei", "den", "die", "das", "der",
            "ein", "eine", "the", "a", "an", "is", "to", "for", "and", "or", "of", "on", "at", "by", "not",
            "[incident]", "[follow-up]", "—", "-", "todo", "fix", "add", "update");

    /**
     * Finds similar open TODOs in the same project.
     * Stage 1: Keyword filter (Jaccard above the configured threshold)
     * Stage 2: AI classification (SAME/RELATED/DIFFERENT) for candidates
     *
     * @param projectId project to search in
     * @param title     title of the new TODO
     * @param excludeId ID to exclude (e.g. the TODO being updated), nullable
     * @return list of similar TODOs with AI classification, sorted by relevance
     */
    public List<SimilarTodo> findSimilar(String projectId, String title, String excludeId) {
        Set<String> titleKeywords = extractKeywords(title);
        if (titleKeywords.isEmpty()) return List.of();
        // Read per call: the threshold can be changed at runtime in the settings.
        double threshold = config.getKeywordThreshold();

        // Stage 1: Keyword filter against all open TODOs in the project
        List<Todo> openTodos = todoRepository.findByProjectIdAndStatusIn(
                projectId, List.of(Todo.Status.OPEN, Todo.Status.IN_PROGRESS));

        List<SimilarTodo> candidates = openTodos.stream()
                .filter(t -> excludeId == null || !t.getId().equals(excludeId))
                .map(t -> {
                    Set<String> otherKeywords = extractKeywords(t.getTitle());
                    double score = jaccardSimilarity(titleKeywords, otherKeywords);
                    return new SimilarTodo(t, score, null);
                })
                .filter(st -> st.keywordScore() >= threshold)
                .sorted((a, b) -> Double.compare(b.keywordScore(), a.keywordScore()))
                .limit(5) // Max 5 candidates for AI check
                .toList();

        if (candidates.isEmpty()) return List.of();

        // Stage 2: AI classification for each candidate
        return candidates.stream()
                .map(candidate -> {
                    Similarity sim = classifyWithAi(title, candidate.todo().getTitle());
                    return new SimilarTodo(candidate.todo(), candidate.keywordScore(), sim);
                })
                .filter(st -> st.similarity() != Similarity.DIFFERENT)
                .toList();
    }

    /**
     * Quick keyword-only check without AI. Used for fast pre-filtering.
     */
    public double keywordSimilarity(String title1, String title2) {
        return jaccardSimilarity(extractKeywords(title1), extractKeywords(title2));
    }

    Set<String> extractKeywords(String title) {
        if (title == null || title.isBlank()) return Set.of();
        return Arrays.stream(title.toLowerCase(Locale.ROOT).split("[\\s,;|()\\[\\]]+"))
                .map(w -> w.replaceAll("[^a-zäöüß0-9-]", ""))
                .filter(w -> w.length() > 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union > 0 ? (double) intersection / union : 0;
    }

    private Similarity classifyWithAi(String newTitle, String existingTitle) {
        try {
            AiProvider provider = aiProviderRegistry.getDefault();
            String prompt = String.format(
                    "Compare these two TODO items and classify their relationship. " +
                    "Respond with EXACTLY one word: SAME, RELATED, or DIFFERENT.\n\n" +
                    "TODO 1 (new): %s\n" +
                    "TODO 2 (existing): %s\n\n" +
                    "SAME = both describe the exact same task/problem\n" +
                    "RELATED = they are about the same component/area but different tasks\n" +
                    "DIFFERENT = unrelated tasks",
                    newTitle, existingTitle);

            AiProvider.ReasoningResult result = provider.reason(
                    new AiProvider.ReasoningRequest(prompt, null, null, 1, 30));

            String response = result.text().trim().toUpperCase(Locale.ROOT);
            if (response.contains("SAME")) return Similarity.SAME;
            if (response.contains("RELATED")) return Similarity.RELATED;
            return Similarity.DIFFERENT;
        } catch (Exception e) {
            log.warn("AI similarity check failed, falling back to keyword-only: {}", e.getMessage());
            // Fallback: treat high keyword overlap as RELATED
            return Similarity.RELATED;
        }
    }
}
