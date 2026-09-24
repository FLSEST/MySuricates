package com.automation.service;

import com.automation.entity.AssistantSession;
import com.automation.entity.PageSuggestion;
import com.automation.entity.WorkflowMemory;
import com.automation.event.AssistantSessionUpdatedEvent;
import com.automation.repository.AssistantSessionRepository;
import com.automation.repository.PageSuggestionRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class PageSuggestionService {

    private static final int THROTTLE_SECONDS = 600; // 10 minutes per page
    private static final int SUGGESTION_TIMEOUT_SECONDS = 60;
    private static final int MAX_SUGGESTIONS = 4;

    private static final String SYSTEM_PROMPT = """
            Du bist MySuricates, eine AI-Workflow-Automatisierungsplattform.
            Deine Aufgabe: Generiere kurze, konkrete Schnellaktionen für eine bestimmte Seite der Plattform.
            Die Schnellaktionen sollen dem User ermöglichen, mit einem Klick relevante Fragen zu stellen.
            Antworte AUSSCHLIESSLICH mit einem JSON-Array von Strings. Keine Erklärungen, kein Markdown.
            Beispiel: ["Was lief heute schief?", "Zeige aktive Workflows", "Was steht an?"]
            """;

    private final AssistantSessionRepository sessionRepository;
    private final PageSuggestionRepository suggestionRepository;
    private final WorkflowMemoryRepository workflowMemoryRepository;
    private final AppSettingsRepository appSettingsRepository;
    private final CommandExecutorService commandExecutorService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;

    /** Tracks last refresh time per page to throttle AI calls. */
    private final Map<String, Instant> lastRefreshAt = new ConcurrentHashMap<>();

    /** Tracks the running async refresh thread for cancellation support. */
    private final AtomicReference<Thread> runningRefresh = new AtomicReference<>();

    /** Cached model for suggestions — loaded at startup, refreshed on settings change. */
    private volatile String cachedSuggestionsModel = null;

    public PageSuggestionService(AssistantSessionRepository sessionRepository,
                                  PageSuggestionRepository suggestionRepository,
                                  WorkflowMemoryRepository workflowMemoryRepository,
                                  AppSettingsRepository appSettingsRepository,
                                  CommandExecutorService commandExecutorService,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.suggestionRepository = suggestionRepository;
        this.workflowMemoryRepository = workflowMemoryRepository;
        this.appSettingsRepository = appSettingsRepository;
        this.commandExecutorService = commandExecutorService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    @PostConstruct
    public void init() {
        appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank())
                .ifPresentOrElse(
                        m -> cachedSuggestionsModel = m,
                        () -> appSettingsRepository.set("suggestions-model", "")
                );
    }

    /** Called by SystemController when suggestions-model setting changes. */
    public void refreshCachedModel() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
        log.info("Suggestions model cache refreshed: {}", cachedSuggestionsModel);
    }

    /** Cancels a running async suggestion refresh (e.g. when chat starts). */
    public void cancelRunning() {
        Thread thread = runningRefresh.getAndSet(null);
        if (thread != null && thread.isAlive()) {
            log.info("Cancelling running suggestion refresh for chat priority");
            thread.interrupt();
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public List<String> getSuggestions(String page) {
        return suggestionRepository.findByPage(page)
                .map(s -> deserialize(s.getSuggestions()))
                .orElseGet(() -> getDefaults(page));
    }

    /**
     * Deliberately not {@code @Transactional}: the model call takes up to 60 s, and with HikariCP
     * pool-size 1 an enclosing transaction would hold the only DB connection for all of it. The
     * prompt is built in a short read burst, the result written in a short write burst.
     */
    public List<String> refreshSuggestions(String page) {
        PromptInput input = readOnlyTransactionTemplate.execute(status -> {
            List<AssistantSession> sessions = sessionRepository.findTop10ByPageOrderByStartedAtDesc(page);
            return new PromptInput(buildPrompt(page, sessions), sessions.size());
        });
        List<String> suggestions = generateViaClaude(page, input.prompt());
        transactionTemplate.executeWithoutResult(status -> persist(page, suggestions, input.sessionCount()));
        lastRefreshAt.put(page, Instant.now());
        return suggestions;
    }

    /** What the read burst of {@link #refreshSuggestions} hands over to the model call. */
    private record PromptInput(String prompt, int sessionCount) {}

    // ── Event listener (async, throttled) ───────────────────────────────────

    @Async
    @EventListener
    public void onSessionUpdated(AssistantSessionUpdatedEvent event) {
        String page = event.page();
        if (!shouldRefresh(page)) return;
        runningRefresh.set(Thread.currentThread());
        try {
            refreshSuggestions(page);
            log.debug("Refreshed suggestions for page '{}' after session update", page);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Suggestion refresh for page '{}' was cancelled", page);
            } else {
                log.warn("Could not refresh suggestions for page '{}'", page, e);
            }
        } finally {
            runningRefresh.compareAndSet(Thread.currentThread(), null);
        }
    }

    // ── Generation ───────────────────────────────────────────────────────────

    private List<String> generateViaClaude(String page, String prompt) {
        try {
            var result = commandExecutorService.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(System.getProperty("user.home"))
                            .command(prompt)
                            .systemPrompt(SYSTEM_PROMPT)
                            .model(cachedSuggestionsModel)
                            .timeoutSeconds(SUGGESTION_TIMEOUT_SECONDS)
                            .build()
            );
            List<String> parsed = parseJsonArray(result.output());
            if (!parsed.isEmpty()) {
                return parsed.stream().limit(MAX_SUGGESTIONS).toList();
            }
        } catch (Exception e) {
            log.warn("Could not generate suggestions via Claude for page '{}'", page, e);
        }
        return getDefaults(page);
    }

    private String buildPrompt(String page, List<AssistantSession> sessions) {
        var sb = new StringBuilder();
        sb.append("Generiere ").append(MAX_SUGGESTIONS)
                .append(" kurze Schnellaktionen (Fragen/Aufgaben) für die Seite '").append(page).append("'.\n\n");

        if (!sessions.isEmpty()) {
            sb.append("Letzte Konversationen auf dieser Seite (Zusammenfassung):\n");
            sessions.stream().limit(5).forEach(s -> {
                List<Map<String, String>> msgs = deserializeToMaps(s.getMessages());
                msgs.stream()
                        .filter(m -> "user".equals(m.get("role")))
                        .limit(2)
                        .forEach(m -> sb.append("- ").append(m.get("content")).append("\n"));
            });
            sb.append("\n");
        }

        List<WorkflowMemory> memories = workflowMemoryRepository.findTop5ByActiveTrueOrderByCreatedAtDesc();
        if (!memories.isEmpty()) {
            sb.append("Bekannte Erkenntnisse aus vergangenen Workflow-Runs:\n");
            memories.forEach(m -> {
                String entry = m.getTitle() != null && !m.getTitle().isBlank()
                        ? m.getTitle() + ": " + m.getContent()
                        : m.getContent();
                if (entry.length() > 120) entry = entry.substring(0, 120) + "…";
                sb.append("- ").append(entry).append("\n");
            });
            sb.append("\n");
        }

        sb.append("Seiten-Kontext: '").append(page).append("' in der MySuricates-Plattform.\n");
        sb.append("Gib NUR das JSON-Array zurück, keine weiteren Texte.");
        return sb.toString();
    }

    private void persist(String page, List<String> suggestions, int sessionCount) {
        String json = serialize(suggestions);
        PageSuggestion entity = suggestionRepository.findByPage(page)
                .orElseGet(() -> PageSuggestion.builder()
                        .id(java.util.UUID.randomUUID().toString())
                        .page(page)
                        .build());
        entity.setSuggestions(json);
        entity.setGeneratedAt(Instant.now());
        entity.setBasedOnSessions(sessionCount);
        suggestionRepository.save(entity);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean shouldRefresh(String page) {
        Instant last = lastRefreshAt.get(page);
        return last == null || Instant.now().minusSeconds(THROTTLE_SECONDS).isAfter(last);
    }

    private List<String> getDefaults(String page) {
        return switch (page) {
            case "run"       -> List.of("Erkläre diesen Run", "Warum ist das fehlgeschlagen?", "Was kann verbessert werden?");
            case "workflow"  -> List.of("Analysiere diesen Workflow", "Zeige letzte Runs", "Gibt es Verbesserungspotenzial?");
            case "workflows" -> List.of("Welche Workflows haben Probleme?", "Was läuft gerade?");
            case "runs"      -> List.of("Was lief heute schief?", "Wie war die Erfolgsrate?");
            case "todos"     -> List.of("Was ist dringend?", "Priorisiere meine Todos");
            case "project"   -> List.of("Was steht in diesem Projekt an?", "Zeige offene Todos");
            case "projects"  -> List.of("Wie laufen meine Projekte?", "Was braucht Aufmerksamkeit?");
            case "schedules" -> List.of("Was steht heute an?", "Gibt es überfällige Schedules?");
            case "activity"  -> List.of("Was ist heute passiert?", "Zeige wichtige Ereignisse");
            case "insights"  -> List.of("Wie war die Woche?", "Was kann verbessert werden?");
            case "wishes"    -> List.of("Was soll ich als nächstes automatisieren?");
            case "factory"   -> List.of("Hilf mir einen Workflow zu erstellen");
            case "chains"    -> List.of("Wie laufen meine Chains?");
            default          -> List.of("Was lief heute?", "Gibt es Probleme?", "Was steht an?");
        };
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        // Find the first [ ... ] in the response
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            return objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not parse suggestion JSON array from: {}", raw);
            return List.of();
        }
    }

    private List<String> deserialize(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> deserializeToMaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serialize(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
