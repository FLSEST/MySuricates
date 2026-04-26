package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_ACTIONS = 4;
    private static final int WORKFLOW_INDEX_LIMIT = 40;
    private static final int RECENT_RUNS_LIMIT = 5;

    /** Navigational types the frontend renders directly as click-through buttons. */
    private static final Set<String> NAV_TYPES = Set.of(
            "show_run", "show_workflow", "start_wish", "ask_choice", "run_workflow");

    /**
     * Mutating-proposal types: server converts them into PendingTools (which the frontend
     * already renders with a Confirm/Reject card). The user-facing flow is identical to a
     * tool tag emitted by the main model — same review UI, same backend executor.
     */
    private static final Set<String> PROPOSE_TYPES = Set.of(
            "propose_create_todo",
            "propose_cancel_run",
            "propose_rerun_workflow",
            "propose_update_todo_status");

    private static final Set<String> KNOWN_TYPES;
    static {
        var all = new java.util.HashSet<String>();
        all.addAll(NAV_TYPES);
        all.addAll(PROPOSE_TYPES);
        KNOWN_TYPES = Set.copyOf(all);
    }

    /** Types that require a non-blank id: navigational entity-buttons + propose_* with target. */
    private static final Set<String> TYPES_REQUIRING_ID = Set.of(
            "show_run", "show_workflow", "run_workflow",
            "propose_cancel_run", "propose_rerun_workflow", "propose_update_todo_status");

    private static final String SYSTEM_PROMPT = """
            Du erzeugst Action-Vorschläge für den MySuricates-Chat — sowohl Navigation als auch
            mitdenkende Mutations-Vorschläge. Der User soll Optionen sehen, was er als Nächstes
            tun könnte. Wenn etwas Sinnvolles ansteht (Bug erkannt → TODO; FAILED-Run → Re-Run;
            hängender Run → Abbrechen), schlage es proaktiv vor — auch wenn der User nicht
            explizit danach fragt. Lieber 1-2 wirklich passende Vorschläge als 4 generische.

            Antworte AUSSCHLIESSLICH mit einem JSON-Array (max 4 Einträge) oder [].
            Keine Erklärungen, kein Markdown, kein ```-Fence, nur das Array.

            Navigations-Typen (User klickt → Seite öffnet):
              show_run       {"type":"show_run","id":"<run-id>","name":"Run-Name","label":"Run öffnen"}
              show_workflow  {"type":"show_workflow","id":"<workflow-id>","name":"Workflow-Name","label":"Workflow öffnen"}
              start_wish     {"type":"start_wish","id":null,"name":"<Wunschtext>","label":"Als Wunsch formulieren"}
              ask_choice     {"type":"ask_choice","id":null,"name":"Auswahl","label":"…","options":["A","B"]}
              run_workflow   {"type":"run_workflow","id":"<workflow-id>","name":"Workflow-Name","label":"Jetzt starten"}

            Mitdenkende Mutations-Vorschläge (User bekommt Confirm/Reject-Karte):
              propose_create_todo
                {"type":"propose_create_todo","id":null,"name":"<TODO-Titel>","label":"TODO anlegen",
                 "payload":{"title":"<TODO-Titel>","priority":"HIGH|MEDIUM|LOW","tags":["bereich","typ"],"description":"<Kurz-Kontext optional>"}}
              propose_cancel_run
                {"type":"propose_cancel_run","id":"<run-id>","name":"<Run-Bezeichnung>","label":"Run abbrechen"}
              propose_rerun_workflow
                {"type":"propose_rerun_workflow","id":"<workflow-id>","name":"<Workflow-Name>","label":"Workflow neu starten"}
              propose_update_todo_status
                {"type":"propose_update_todo_status","id":"<todo-id>","name":"<TODO-Titel>","label":"Auf DONE setzen",
                 "payload":{"status":"DONE|OPEN|IN_PROGRESS|BACKLOG"}}

            Beispiele für sinnvolles Mitdenken:
            - User auf Run-Page mit FAILED-Status: `propose_rerun_workflow` (workflow-id aus dem Kontext)
              und ggf. `propose_create_todo` mit einer Bug-Diagnose als Titel.
            - User schreibt eine Bug-Beobachtung oder Feature-Idee: `propose_create_todo` mit
              passendem Titel + Tags. Tags-Konvention: `[bereich, typ]` z.B. `["backend","bug"]`,
              `["frontend","ux"]`, `["assistant","feature"]`.
            - Run-Page mit Status RUNNING und Laufzeit > 10 Min in einem normal-kurzen Workflow:
              `propose_cancel_run` mit der run-id der Page.
            - User sagt „ist erledigt" o.ä. auf TODO-Page: `propose_update_todo_status` (status=DONE)
              mit der todo-id aus dem Kontext.

            Regeln:
            - Nutze ausschließlich IDs aus dem gelieferten Kontext (Page-Context, Workflow-Index,
              Recent-Runs). Keine Erfindung von IDs.
            - `payload` muss reines ASCII-JSON sein — keine Unicode-Vollbreite-Kommas (`，`).
            - Bei mehrdeutigem Bezug → `ask_choice` statt zu raten.
            - `run_workflow` (direkter Start ohne Confirm) NUR wenn der User explizit „starten/run/los"
              sagt. Sonst lieber `propose_rerun_workflow`.
            - Passt nichts → gib [] zurück. Lieber leer als schlecht.
            """;

    private final CommandExecutorService commandExecutorService;
    private final AppSettingsRepository appSettingsRepository;
    private final ObjectMapper objectMapper;
    private final PendingToolRegistry pendingToolRegistry;
    private final AssistantToolExecutor toolExecutor;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;

    /** Cached suggestions model — loaded at startup, refreshed on settings change. */
    private volatile String cachedSuggestionsModel = null;

    /** Cached on/off flag for suggestion generation (shared with page-suggestions). */
    private volatile boolean cachedSuggestionsEnabled = true;

    /** Default model for the suggestion call. Sonnet is fast enough for the 30s window and
     *  produces noticeably better proposals than the CLI's local default (typically Haiku).
     *  Per memory rule: never Haiku for chat/assistant. */
    private static final String DEFAULT_SUGGESTIONS_MODEL = "claude-sonnet-4-6";

    @PostConstruct
    public void init() {
        // Seed an explicit default if the setting is empty — otherwise the CLI falls back to
        // its local default model which produces lower-quality suggestions.
        if (appSettingsRepository.get("suggestions-model").filter(m -> !m.isBlank()).isEmpty()) {
            appSettingsRepository.set("suggestions-model", DEFAULT_SUGGESTIONS_MODEL);
        }
        cachedSuggestionsModel = appSettingsRepository.get("suggestions-model")
                .filter(m -> !m.isBlank()).orElse(DEFAULT_SUGGESTIONS_MODEL);
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
     * Result of a suggestion call: navigational actions (rendered as click buttons) and
     * mutating proposals already converted to {@link PendingTool}s (rendered as confirm cards
     * by the same UI that handles {@code <tool>}-tag proposals).
     */
    public record SuggestionResult(List<AssistantAction> actions, List<PendingTool> pendingTools) {
        public static SuggestionResult empty() {
            return new SuggestionResult(List.of(), List.of());
        }
    }

    /**
     * Generates action suggestions and mutating-proposal pending-tools for the given chat turn.
     * Returns an empty result on any error, timeout or when suggestions are disabled.
     */
    public SuggestionResult suggest(AssistantChatRequest req, String assistantAnswer, String pageContext) {
        if (!cachedSuggestionsEnabled) {
            return SuggestionResult.empty();
        }
        if (assistantAnswer == null || assistantAnswer.isBlank()) {
            return SuggestionResult.empty();
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
                return SuggestionResult.empty();
            }
            List<AssistantAction> parsed = parseActions(result.output());
            return splitAndConvert(validate(parsed));
        } catch (Exception e) {
            log.debug("Action-suggestion call failed; returning empty actions", e);
            return SuggestionResult.empty();
        }
    }

    /**
     * Splits validated actions into (a) plain navigational actions and (b) propose_* actions
     * which are converted to PendingTools. PendingTools that fail to convert (missing ids,
     * unresolvable workflow lookup) are dropped silently — the chat turn still completes.
     */
    private SuggestionResult splitAndConvert(List<AssistantAction> validated) {
        List<AssistantAction> nav = new ArrayList<>();
        List<PendingTool> tools = new ArrayList<>();
        for (AssistantAction a : validated) {
            if (PROPOSE_TYPES.contains(a.type())) {
                PendingTool tool = convertToPendingTool(a);
                if (tool != null) tools.add(tool);
            } else {
                nav.add(a);
            }
        }
        return new SuggestionResult(nav, tools);
    }

    /**
     * Maps a propose_* action onto the corresponding existing {@code AssistantToolExecutor}
     * tool type and registers it as a pending-tool. Returns {@code null} when required
     * payload fields are missing or invalid — better to drop the suggestion than surface a
     * Confirm card that errors on click.
     */
    private PendingTool convertToPendingTool(AssistantAction action) {
        try {
            String type = action.type();
            Map<String, Object> payload = action.payload() != null
                    ? new LinkedHashMap<>(action.payload())
                    : new LinkedHashMap<>();
            String toolType;
            switch (type) {
                case "propose_create_todo" -> {
                    toolType = "create_todo";
                    if (!payload.containsKey("title") || isBlank(payload.get("title"))) {
                        // fall back to action.name if payload didn't carry the title
                        if (action.name() != null && !action.name().isBlank()) {
                            payload.put("title", action.name());
                        } else {
                            return null;
                        }
                    }
                    payload.putIfAbsent("priority", "MEDIUM");
                }
                case "propose_cancel_run" -> {
                    toolType = "cancel_run";
                    if (action.id() == null || action.id().isBlank()) return null;
                    payload.put("runId", action.id());
                }
                case "propose_rerun_workflow" -> {
                    toolType = "start_workflow";
                    if (action.id() == null || action.id().isBlank()) return null;
                    payload.put("workflowId", action.id());
                }
                case "propose_update_todo_status" -> {
                    toolType = "update_todo_status";
                    if (action.id() == null || action.id().isBlank()) return null;
                    if (!payload.containsKey("status") || isBlank(payload.get("status"))) return null;
                    payload.put("id", action.id());
                }
                default -> {
                    return null;
                }
            }
            String payloadJson = objectMapper.writeValueAsString(payload);
            String description = toolExecutor.describe(toolType, payloadJson);
            return pendingToolRegistry.register(toolType, description, payloadJson);
        } catch (Exception e) {
            log.debug("Could not convert propose-action to pending-tool: {}", action, e);
            return null;
        }
    }

    private static boolean isBlank(Object v) {
        return v == null || v.toString().isBlank();
    }

    // ── Prompt building ──────────────────────────────────────────────────────

    private String buildPrompt(AssistantChatRequest req, String assistantAnswer, String pageContext) {
        var sb = new StringBuilder(3500);
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

        // Page entity: explicit so the suggestion model can refer to "this run"/"this workflow"
        // by id without re-deriving it from the page-context blob.
        if (req != null && req.context() != null && req.context().entityId() != null) {
            sb.append("Aktuelle Entity-ID auf dieser Seite: ").append(req.context().entityId()).append("\n\n");
        }

        // Workflow index — names + IDs only — so the model can propose run_workflow /
        // propose_rerun_workflow with a real ID without it appearing in the answer text.
        appendWorkflowIndex(sb);

        // Recent runs — gives the model run-ids for propose_cancel_run / propose_rerun_workflow.
        appendRecentRuns(sb);

        sb.append("User-Frage:\n");
        sb.append(req != null && req.message() != null ? req.message() : "").append("\n\n");

        sb.append("Antwort des Assistenten:\n");
        sb.append(assistantAnswer).append("\n\n");

        sb.append("Erzeuge jetzt das JSON-Array mit maximal ")
                .append(MAX_ACTIONS)
                .append(" Action-Vorschlägen (oder [] falls nichts passt).");
        return sb.toString();
    }

    /** Compact list of (id, name) for top workflows by recently-updated, capped to keep
     *  the prompt small. Skipped silently on repository errors. */
    private void appendWorkflowIndex(StringBuilder sb) {
        try {
            List<Workflow> all = workflowRepository.findAll();
            if (all == null || all.isEmpty()) return;
            sb.append("Workflow-Index (nutze diese IDs, falls passend):\n");
            all.stream()
                    .sorted((a, b) -> {
                        var aU = a.getUpdatedAt();
                        var bU = b.getUpdatedAt();
                        if (aU == null && bU == null) return 0;
                        if (aU == null) return 1;
                        if (bU == null) return -1;
                        return bU.compareTo(aU);
                    })
                    .limit(WORKFLOW_INDEX_LIMIT)
                    .forEach(w -> sb.append("  - ").append(w.getId())
                            .append(" | ").append(w.getName())
                            .append('\n'));
            sb.append('\n');
        } catch (Exception e) {
            log.debug("Could not append workflow index to suggestion prompt", e);
        }
    }

    /** Compact list of recent runs for cancel/rerun suggestions. */
    private void appendRecentRuns(StringBuilder sb) {
        try {
            var page = workflowRunRepository.findAll(PageRequest.of(0, RECENT_RUNS_LIMIT));
            List<WorkflowRun> runs = page == null ? List.of() : page.getContent();
            if (runs.isEmpty()) return;
            sb.append("Letzte Runs (nutze diese IDs für cancel/rerun):\n");
            for (WorkflowRun r : runs) {
                String wfId;
                String wfName;
                try {
                    var wf = r.getWorkflow();
                    wfId = wf == null ? "?" : wf.getId();
                    wfName = wf == null ? "?" : wf.getName();
                } catch (Exception lazyErr) {
                    wfId = "?";
                    wfName = "?";
                }
                sb.append("  - ").append(r.getId())
                        .append(" | ").append(r.getStatus())
                        .append(" | workflow=").append(wfId)
                        .append(" (").append(wfName).append(')')
                        .append('\n');
            }
            sb.append('\n');
        } catch (Exception e) {
            log.debug("Could not append recent runs to suggestion prompt", e);
        }
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
