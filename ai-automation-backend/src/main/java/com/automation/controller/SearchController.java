package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.Workflow;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.search.SemanticWorkflowSearchService;
import com.automation.service.search.SemanticWorkflowSearchService.SemanticHit;
import com.automation.service.workflow.WorkflowService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates search across workflows (and later runs/skills/integrations).
 * Lightweight substring/fuzzy ranking today; semantic ranking lives in the
 * sibling POST endpoint that calls the suggestions-model on demand.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final SemanticWorkflowSearchService semanticSearchService;

    public record SearchResponse(List<WorkflowResponse> workflows) {}

    public record SemanticSearchRequest(@NotBlank String query, Integer limit) {}

    public record SemanticSearchResult(
            String workflowId,
            String workflowName,
            String reason,
            double score
    ) {}

    /**
     * Searches workflows by name, description, tags and category for {@code q} and returns the best matches.
     * An empty {@code q} yields an empty list; {@code limit} (default 20) is capped at 50.
     */
    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "workflow") String types,
            @RequestParam(defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            return new SearchResponse(List.of());
        }
        int capped = Math.max(1, Math.min(limit, 50));
        List<WorkflowResponse> workflows = List.of();
        if (types.contains("workflow")) {
            workflows = rankWorkflows(q, capped);
        }
        return new SearchResponse(workflows);
    }

    /**
     * Ranks workflows against a natural-language {@code query} with the AI suggestions model, each with a match reason.
     * Answers 204 when semantic search is switched off ({@code automation.discovery.semantic-enabled}).
     */
    @PostMapping("/workflows/semantic")
    public ResponseEntity<List<SemanticSearchResult>> semantic(@RequestBody SemanticSearchRequest request) {
        if (!semanticSearchService.isEnabled()) {
            return ResponseEntity.noContent().build();
        }
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        int limit = request.limit() == null ? 5 : request.limit();
        List<SemanticHit> hits = semanticSearchService.search(request.query(), limit);
        if (hits.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        Map<String, String> namesById = workflowService.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Workflow::getId, Workflow::getName));
        List<SemanticSearchResult> body = hits.stream()
                .map(h -> new SemanticSearchResult(
                        h.workflowId(),
                        namesById.getOrDefault(h.workflowId(), "(unknown)"),
                        h.reason(),
                        h.score()))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ── Ranking ──────────────────────────────────────────────────────────────

    private List<WorkflowResponse> rankWorkflows(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Workflow> ranked = workflowService.findAll().stream()
                .map(w -> new Scored(w, scoreWorkflow(w, q)))
                .filter(s -> s.score > 0)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(limit)
                .map(s -> s.workflow)
                .toList();
        // Map in bulk, not per hit: the single path resolves the run count per workflow
        // and produced 20 additional select workflow_runs for 20 hits. /workflows has used
        // the bulk variant since 1e465a9e, the search was left out (TODO 13d1e2a2).
        return workflowMapper.toResponseListWithRunCounts(ranked);
    }

    private static double scoreWorkflow(Workflow w, String q) {
        double score = 0;
        String name = lower(w.getName());
        String description = lower(w.getDescription());
        String category = lower(w.getCategory());
        String tags = w.getTags() == null ? "" : String.join(" ", w.getTags()).toLowerCase(Locale.ROOT);

        if (name.equals(q)) score += 10;
        if (name.startsWith(q)) score += 5;
        if (name.contains(q)) score += 3;
        if (description.contains(q)) score += 1.5;
        if (tags.contains(q)) score += 2;
        if (category.contains(q)) score += 1;
        return score;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private record Scored(Workflow workflow, double score) {}
}
