package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Generates action-button suggestions for chat replies via a second, lightweight CLI call
 * that uses the {@code suggestions-model} setting (typically Haiku). Keeps the main chat
 * model focused on producing a good text answer — this service reads the page context,
 * the user question and the assistant's answer and returns structured JSON actions.
 *
 * <p>On error, timeout or unparseable output this returns an empty list so the chat still
 * delivers its text response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantActionSuggestionService {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_ACTIONS = 3;

    private static final Set<String> KNOWN_TYPES = Set.of(
            "show_run", "show_workflow", "start_wish", "ask_choice", "run_workflow");
    private static final Set<String> TYPES_REQUIRING_ID = Set.of(
            "show_run", "show_workflow", "run_workflow");

    private static final String SYSTEM_PROMPT = """
            Du erzeugst Action-Button-Vorschläge für den MySuricates-Chat.
            Antworte AUSSCHLIESSLICH mit einem JSON-Array (max 3 Einträge) oder [].
            Keine Erklärungen, kein Markdown, kein ```-Fence, nur das Array.

            Schema (genau diese Felder, nichts darüber hinaus):
              show_run       {"type":"show_run","id":"<run-id>","name":"Run-Name","label":"Run öffnen"}
              show_workflow  {"type":"show_workflow","id":"<workflow-id>","name":"Workflow-Name","label":"Workflow öffnen"}
              start_wish     {"type":"start_wish","id":null,"name":"<Wunschtext>","label":"Als Wunsch formulieren"}
              ask_choice     {"type":"ask_choice","id":null,"name":"Auswahl","label":"…","options":["A","B"]}
              run_workflow   {"type":"run_workflow","id":"<workflow-id>","name":"Workflow-Name","label":"Jetzt starten"}

            Regeln:
            - Nutze ausschließlich IDs aus dem gelieferten Kontext. Keine Erfindung.
            - `run_workflow` NUR wenn der User in der Frage explizit starten/rerunnen will.
            - Nennt der Chat-Text einen Run/Workflow namentlich → `show_run` / `show_workflow`.
            - Bei mehreren passenden Workflows → `ask_choice` mit Workflow-Namen als Optionen.
            - Feature-/Verbesserungs-Wunsch in der Frage → `start_wish` mit dem Wunschtext.
            - Passt nichts → gib [] zurück. Lieber leer als schlecht.
            """;

    private final CommandExecutorService commandExecutorService;
    private final AppSettingsRepository appSettingsRepository;
    private final ObjectMapper objectMapper;

    /** Cached suggestions model — loaded at startup, refreshed on settings change. */
    private volatile String cachedSuggestionsModel = null;

    /** Cached on/off flag for suggestion generation (shared with page-suggestions). */
    private volatile boolean cachedSuggestionsEnabled = true;

    @PostConstruct
    public void init() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
        cachedSuggestionsEnabled = appSettingsRepository.get("suggestions-enabled")
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }

    /** Called by {@code SystemSettingsService} when relevant settings change. */
    public void refreshCache() {
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(null);
        cachedSuggestionsEnabled = appSettingsRepository.get("suggestions-enabled")
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
        log.info("Action-suggestion cache refreshed: model={}, enabled={}",
                cachedSuggestionsModel, cachedSuggestionsEnabled);
    }

    public boolean isEnabled() {
        return cachedSuggestionsEnabled;
    }

    /**
     * Generates action suggestions for the given chat turn. Returns an empty list on any
     * error, timeout or when suggestions are disabled.
     */
    public List<AssistantAction> suggest(AssistantChatRequest req, String assistantAnswer, String pageContext) {
        if (!cachedSuggestionsEnabled) {
            return List.of();
        }
        if (assistantAnswer == null || assistantAnswer.isBlank()) {
            return List.of();
        }

        String prompt = buildPrompt(req, assistantAnswer, pageContext);
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
                log.debug("Action-suggestion CLI exited {}; skipping actions", result.exitCode());
                return List.of();
            }
            List<AssistantAction> parsed = parseActions(result.output());
            return validate(parsed);
        } catch (Exception e) {
            log.debug("Action-suggestion call failed; returning empty actions", e);
            return List.of();
        }
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    private String buildPrompt(AssistantChatRequest req, String assistantAnswer, String pageContext) {
        var sb = new StringBuilder(2000);
        sb.append("Seiten-Kontext (nutze nur IDs und Namen aus diesem Abschnitt):\n");
        if (pageContext != null && !pageContext.isBlank()) {
            String trimmed = pageContext.length() > 6000
                    ? pageContext.substring(0, 6000) + "\n…[gekürzt]"
                    : pageContext;
            sb.append(trimmed).append("\n\n");
        } else {
            sb.append("(kein zusätzlicher Kontext)\n\n");
        }

        sb.append("Aktueller Seiten-Pfad: ");
        if (req != null && req.context() != null && req.context().page() != null) {
            sb.append("/").append(req.context().page());
        } else {
            sb.append("(unbekannt)");
        }
        sb.append("\n\n");

        sb.append("User-Frage:\n");
        sb.append(req != null && req.message() != null ? req.message() : "").append("\n\n");

        sb.append("Antwort des Assistenten:\n");
        sb.append(assistantAnswer).append("\n\n");

        sb.append("Erzeuge jetzt das JSON-Array mit maximal ")
                .append(MAX_ACTIONS)
                .append(" Action-Vorschlägen (oder [] falls nichts passt).");
        return sb.toString();
    }

    // ── Parsing / validation ─────────────────────────────────────────────────

    private List<AssistantAction> parseActions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            List<AssistantAction> actions = objectMapper.readValue(
                    raw.substring(start, end + 1),
                    new TypeReference<>() {});
            return actions == null ? List.of() : actions;
        } catch (Exception e) {
            log.debug("Could not parse action-suggestion JSON: {}", raw, e);
            return List.of();
        }
    }

    private List<AssistantAction> validate(List<AssistantAction> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream()
                .filter(a -> a != null && a.type() != null)
                .filter(a -> KNOWN_TYPES.contains(a.type()))
                .filter(a -> !TYPES_REQUIRING_ID.contains(a.type())
                        || (a.id() != null && !a.id().isBlank()))
                .limit(MAX_ACTIONS)
                .toList();
    }
}
