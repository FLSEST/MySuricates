package com.automation.service.search;

import com.automation.entity.Workflow;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.WorkflowContentChangedEvent;
import org.springframework.context.event.EventListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intent-based workflow search via the {@code suggestions-model}. Gives the model a compact
 * index ({id, name, description, tags, category, blockTypes}) and a natural-language query,
 * and asks it to rank the top matches with a one-line match reason.
 *
 * <p>Results cached by (query, workflows-fingerprint) for 5 min to absorb repeated typing.
 * Feature-flag {@code automation.discovery.semantic-enabled} can disable the service.
 */
@Service
@Slf4j
public class SemanticWorkflowSearchService {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final long CACHE_TTL_MS = 5 * 60_000;
    private static final int CACHE_MAX = 500;

    private static final String SYSTEM_PROMPT = """
            Du rankst MySuricates-Workflows nach Match zur User-Anfrage.
            Antworte AUSSCHLIESSLICH mit einem JSON-Array — kein Markdown, kein ```-Fence, keine Erklärungen.

            Schema (genau):
              [{"workflowId":"<id aus Index>","reason":"kurzer Grund (max 80 Zeichen)","score":0.0-1.0}]

            Regeln:
            - Nutze ausschließlich workflowIds aus dem gelieferten Index.
            - Maximal die geforderte Anzahl Treffer, sortiert nach score absteigend.
            - Keine Erfindung: wenn nichts klar passt → [] zurückgeben.
            - score = 1.0 perfekter Treffer, 0.0 passt nicht. Nur Scores ≥ 0.4 zurückgeben.
            """;

    private final CommandExecutorService commandExecutorService;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnlyTransactionTemplate;

    @Value("${automation.discovery.semantic-enabled:true}")
    private boolean semanticEnabled;

    private volatile String cachedSuggestionsModel = null;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private record CachedResult(List<SemanticHit> data, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public record SemanticHit(String workflowId, String reason, double score) {}

    public SemanticWorkflowSearchService(CommandExecutorService commandExecutorService,
                                         AppSettingsRepository appSettingsRepository,
                                         WorkflowRepository workflowRepository,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.commandExecutorService = commandExecutorService;
        this.appSettingsRepository = appSettingsRepository;
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    @PostConstruct
    public void init() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
    }

    public void refreshCache() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
        cache.clear();
        log.info("Semantic-search cache refreshed: model={}", cachedSuggestionsModel);
    }

    public void invalidate() {
        cache.clear();
    }

    @EventListener
    public void onContentChanged(WorkflowContentChangedEvent event) {
        cache.clear();
    }

    public boolean isEnabled() {
        return semanticEnabled;
    }

    /**
     * Deliberately not {@code @Transactional}: the model call takes up to 30 s, and with HikariCP
     * pool-size 1 an enclosing transaction would hold the only DB connection for all of it. The index
     * is read in a short read-only burst instead; the prompt only touches plain columns of the
     * returned workflows, so they need no open session afterwards.
     */
    public List<SemanticHit> search(String query, int limit) {
        if (!semanticEnabled) return List.of();
        if (query == null || query.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        List<Workflow> workflows = readOnlyTransactionTemplate.execute(
                status -> workflowRepository.findAllWithActiveProject());
        if (workflows == null || workflows.isEmpty()) return List.of();

        String fingerprint = fingerprint(workflows);
        String cacheKey = capped + ":" + query.trim().toLowerCase() + ":" + fingerprint;

        CachedResult cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data();
        }

        List<SemanticHit> hits = callModel(query, workflows, capped);
        if (hits == null) return List.of();

        if (cache.size() > CACHE_MAX) {
            cache.clear();
        }
        cache.put(cacheKey, new CachedResult(hits, System.currentTimeMillis()));
        return hits;
    }

    // ── Model call ───────────────────────────────────────────────────────────

    private List<SemanticHit> callModel(String query, List<Workflow> workflows, int limit) {
        String prompt = buildPrompt(query, workflows, limit);
        try {
            var result = commandExecutorService.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(System.getProperty("user.home"))
                            .command(prompt)
                            .systemPrompt(SYSTEM_PROMPT)
                            .model(cachedSuggestionsModel)
                            .timeoutSeconds(TIMEOUT_SECONDS)
                            .priority(false)
                            .build()
            );
            if (result.exitCode() != 0) {
                log.debug("Semantic-search CLI exited {}", result.exitCode());
                return null;
            }
            return parseAndValidate(result.output(), workflows, limit);
        } catch (Exception e) {
            log.debug("Semantic-search CLI failed", e);
            return null;
        }
    }

    private String buildPrompt(String query, List<Workflow> workflows, int limit) {
        var sb = new StringBuilder(4000);
        sb.append("Workflow-Index (nur diese IDs zurückgeben):\n");
        for (Workflow w : workflows) {
            sb.append("- ").append(w.getId()).append(" | ");
            sb.append(safe(w.getName())).append(" | ");
            String desc = safe(w.getDescription());
            if (desc.length() > 160) desc = desc.substring(0, 160) + "…";
            sb.append(desc).append(" | ");
            sb.append("cat=").append(w.getCategory() == null ? "—" : w.getCategory()).append(" | ");
            sb.append("tags=").append(w.getTags() == null || w.getTags().isEmpty()
                    ? "—" : String.join(",", w.getTags())).append(" | ");
            sb.append("blocks=").append(w.getBlockTypes() == null ? "—" : w.getBlockTypes());
            sb.append('\n');
        }
        sb.append("\nUser-Anfrage:\n").append(query.trim()).append("\n\n");
        sb.append("Gib die Top ").append(limit).append(" Treffer zurück (oder [] wenn nichts passt).");
        return sb.toString();
    }

    private List<SemanticHit> parseAndValidate(String raw, List<Workflow> workflows, int limit) {
        if (raw == null || raw.isBlank()) return List.of();
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    raw.substring(start, end + 1), new TypeReference<>() {});
            var validIds = new java.util.HashSet<String>();
            for (Workflow w : workflows) validIds.add(w.getId());

            List<SemanticHit> result = new ArrayList<>();
            for (Map<String, Object> row : parsed) {
                Object idObj = row.get("workflowId");
                Object reasonObj = row.get("reason");
                Object scoreObj = row.get("score");
                if (!(idObj instanceof String id) || !validIds.contains(id)) continue;
                String reason = reasonObj instanceof String r ? r : "";
                double score;
                if (scoreObj instanceof Number n) score = n.doubleValue();
                else if (scoreObj instanceof String s) {
                    try { score = Double.parseDouble(s); } catch (Exception e) { continue; }
                } else continue;
                if (score < 0.4 || score > 1.0) continue;
                result.add(new SemanticHit(id, reason.length() > 80 ? reason.substring(0, 80) : reason, score));
                if (result.size() >= limit) break;
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not parse semantic-search JSON: {}", raw, e);
            return List.of();
        }
    }

    private String fingerprint(List<Workflow> workflows) {
        int h = 1;
        for (Workflow w : workflows) {
            h = 31 * h + (w.getId() == null ? 0 : w.getId().hashCode());
            h = 31 * h + (w.getUpdatedAt() == null ? 0 : w.getUpdatedAt().hashCode());
        }
        return Integer.toHexString(h);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }
}
