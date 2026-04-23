package com.automation.service;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.assistant.AssistantActionSuggestionService;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantContextBuilder;
import com.automation.service.assistant.AssistantFetchService;
import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the assistant chat flow.
 * Delegates to:
 * - {@link AssistantContextBuilder} for system/user prompt construction
 * - {@link AssistantFetchService} for dynamic fetch resolution
 * - {@link AssistantToolExecutor} for tool execution (create_todo, create_workflow, etc.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantService {

    /**
     * Read-only tool whitelist for the assistant chat CLI. Edit, Write, MultiEdit, NotebookEdit,
     * Bash, Task, TodoWrite and others are <b>not</b> in this list — the CLI will refuse to call
     * them, so the model cannot mutate files or run shell commands from within the chat flow.
     * File mutations must go through MySuricates workflow blocks instead.
     */
    private static final List<String> CHAT_ALLOWED_TOOLS = List.of("Read", "Grep", "Glob", "WebFetch");
    /** Maximum number of fetch→answer follow-up calls. Each round ≈ one CLI round-trip. */
    private static final int MAX_FETCH_ROUNDS = 3;
    /** Maximum number of distinct fetches per round. Dedup across rounds runs before this limit. */
    private static final int MAX_FETCHES_PER_ROUND = 3;
    private static final Set<String> AUTO_FETCH_TYPES = Set.of(
            "run_detail",
            "step_output",
            "wish_detail",
            "workflow_runs",
            "workflow_detail",
            "workflow-inputs",
            "find_workflow",
            "project_files",
            "project_file",
            "docs",
            "skill",
            "integration",
            "chain",
            "block-spec"
    );
    private static final Set<String> MUTATING_ACTION_TYPES = Set.of("run_workflow");
    private static final Pattern EXPLICIT_MUTATION_VERB =
            Pattern.compile("(?iu)\\b(?:leg(?:e|en|st)?|erstell(?:e|en|st)?|schreib(?:e|en|st)?|mach(?:e|en|st)?|start(?:e|en|est)?|ausf(?:u|ü)hr(?:e|en|st)?|aktualisier(?:e|en|st)?|(?:ver)?änder(?:e|n|st)?|l(?:o|ö)sch(?:e|en|st)?|entfern(?:e|en|st)?|stopp(?:e|en)?|brich(?:\\s+\\w+){0,2}\\s+ab|markier(?:e|en|st)?)\\b");
    private static final Pattern EXPLICIT_MUTATION_TARGET =
            Pattern.compile("(?iu)\\b(?:todo|aufgabe|workflow|chain|run|ausf(?:u|ü)hrung|wish|wunsch|status)\\b");
    private static final Pattern CONTEXTUAL_RUN_VERB =
            Pattern.compile("(?iu)\\b(?:start(?:e|en|est)?|ausf(?:u|ü)hr(?:e|en|st)?|erneut(?:\\s+ausf(?:u|ü)hren)?|nochmal(?:\\s+ausf(?:u|ü)hren)?|nochmals(?:\\s+ausf(?:u|ü)hren)?|wiederholen|retry|rerun|abbrechen|stopp(?:e|en)?)\\b");

    private static final String DEFAULT_THINKING_PHRASES =
            "{\"de\":[\"Denke nach…\",\"Analysiere…\",\"Überlege kurz…\",\"Einen Moment…\"," +
            "\"Bereite Antwort vor…\",\"Lade Kontext…\",\"Formuliere…\",\"Gleich fertig…\"]," +
            "\"en\":[\"Thinking…\",\"Analyzing…\",\"Just a moment…\",\"Loading context…\"," +
            "\"Preparing answer…\",\"Formulating…\",\"Almost there…\",\"Processing…\"]}";

    private final CommandExecutorService commandExecutorService;
    private final AiProviderRegistry aiProviderRegistry;
    private final AssistantContextBuilder contextBuilder;
    private final AssistantFetchService fetchService;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantChatBroadcaster broadcaster;
    private final AppSettingsRepository appSettingsRepository;
    private final HostClaudeConfig hostClaudeConfig;
    private final ObjectMapper objectMapper;
    private final AssistantActionSuggestionService actionSuggestionService;

    /** Cached model — loaded at startup, never read during a web request. */
    private volatile String cachedAssistantModel = null;

    /** Cached base instructions — loaded once from classpath resource at startup. */
    private volatile String cachedBaseInstructions = null;

    @PostConstruct
    public void seedSettings() {
        String existing = appSettingsRepository.get("assistant-thinking-phrases").orElse(null);
        if (existing == null || existing.trim().startsWith("[")) {
            appSettingsRepository.set("assistant-thinking-phrases", DEFAULT_THINKING_PHRASES);
        }
        if (appSettingsRepository.get("assistant-model").filter(m -> !m.isBlank()).isEmpty()) {
            appSettingsRepository.set("assistant-model", "claude-sonnet-4-6");
        }
        cachedAssistantModel = appSettingsRepository.get("assistant-model")
                .filter(m -> !m.isBlank()).orElse(null);

        try {
            cachedBaseInstructions = new ClassPathResource("assistant-system-prompt.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            log.info("Loaded assistant base instructions ({} chars)", cachedBaseInstructions.length());
        } catch (Exception e) {
            log.warn("Could not load assistant-system-prompt.md — falling back to empty base instructions", e);
            cachedBaseInstructions = "";
        }
    }

    /** Called by SystemController when assistant-model setting changes. */
    public void refreshCachedModel() {
        cachedAssistantModel = appSettingsRepository.get("assistant-model")
                .filter(m -> !m.isBlank()).orElse(null);
        log.info("Assistant model cache refreshed: {}", cachedAssistantModel);
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    public AssistantChatResponse chat(AssistantChatRequest req) {
        long startNanos = System.nanoTime();
        String requestId = req.requestId();
        try {
            broadcaster.broadcastStatus(requestId, "BUILDING_CONTEXT", null, startNanos);
            String systemPrompt = contextBuilder.buildSystemPrompt(req.context(), cachedBaseInstructions);
            String prompt = contextBuilder.buildPrompt(req.history(), req.message());
            String workingDir = contextBuilder.resolveWorkingDir(req.context());
            String model = cachedAssistantModel;
            String resolvedProvider = aiProviderRegistry.resolve(req.provider()).getId();
            boolean allowMutatingProposals = hasExplicitMutationIntent(req);

            broadcaster.broadcastStatus(requestId, "THINKING", null, startNanos);
            var result = executeOrFail(
                    HostExecutionRequest.builder()
                            .projectPath(workingDir)
                            .command(prompt)
                            .systemPrompt(systemPrompt)
                            .providerId(req.provider())
                            .model(model)
                            .timeoutSeconds(hostClaudeConfig.getAssistantChatTimeoutSeconds())
                            .priority(true)
                            .allowedTools(CHAT_ALLOWED_TOOLS)
                            .build()
            );

            // Demand-driven auto-fetch: up to MAX_FETCH_ROUNDS follow-up calls, each with up to
            // MAX_FETCHES_PER_ROUND distinct fetches. We continue the loop whenever the model
            // emits an auto-fetchable tag AND the surrounding text is not a substantive answer
            // (short announcements like "Ich schaue mir an…" count as no-answer, because they
            // are stripped together with the fetch tag in the old logic and otherwise leave
            // the user with just the announcement). An `<actions>` block marks a substantive
            // answer even if the text is short.
            // Duplicates across rounds are filtered so the model can't loop on the same fetch.
            // If after all rounds we still only see <fetch> tags, emit a targeted fallback
            // message instead of "(Keine Antwort erhalten)".
            Set<String> seenFetches = new HashSet<>();
            String accumulatedSupplemental = "";
            boolean fetchLoopEntered = false;
            for (int fetchRound = 0; fetchRound < MAX_FETCH_ROUNDS; fetchRound++) {
                String rawOutput = result.output();
                String nonFetchText = fetchService.stripFetchTags(rawOutput);
                if (hasSubstantiveAnswer(rawOutput, nonFetchText)) break;

                List<FetchRequest> fetches = fetchService.parseFetchRequests(rawOutput).stream()
                        .filter(this::isAutoFetchAllowed)
                        .filter(f -> seenFetches.add(fetchKey(f)))
                        .limit(MAX_FETCHES_PER_ROUND)
                        .toList();
                if (fetches.isEmpty()) break;
                fetchLoopEntered = true;

                broadcaster.broadcastStatus(requestId, "FETCHING_DATA", "Runde " + (fetchRound + 1), startNanos);
                String supplemental = fetches.stream()
                        .map(fetchService::executeFetch)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                log.debug("Assistant fetch round {} — {} fetch(es): {}",
                        fetchRound + 1, fetches.size(),
                        fetches.stream().map(this::fetchKey).toList());
                if (supplemental.isBlank()) break;

                accumulatedSupplemental = accumulatedSupplemental.isBlank()
                        ? supplemental
                        : accumulatedSupplemental + "\n\n" + supplemental;
                String enrichedPrompt = prompt
                        + "\n\n---\nNachgeladene Details (Runde " + (fetchRound + 1) + "):\n"
                        + accumulatedSupplemental
                        + "\n\n[Bitte beantworte die ursprüngliche Frage jetzt mit diesen Details. "
                        + "Wenn wirklich noch etwas Entscheidendes fehlt, benenne es in 1 Satz und mach dann "
                        + "höchstens einen weiteren konkreten <fetch>.]";
                broadcaster.broadcastStatus(requestId, "THINKING", "Runde " + (fetchRound + 1), startNanos);
                result = executeOrFail(
                        HostExecutionRequest.builder()
                                .projectPath(workingDir)
                                .command(enrichedPrompt)
                                .systemPrompt(systemPrompt)
                                .providerId(req.provider())
                                .model(model)
                                .timeoutSeconds(hostClaudeConfig.getAssistantChatTimeoutSeconds())
                                .priority(true)
                                .allowedTools(CHAT_ALLOWED_TOOLS)
                                .build()
                );
            }

            // Extract tool proposals (NOT executed — user must confirm via controller endpoint)
            broadcaster.broadcastStatus(requestId, "EXECUTING_TOOLS", null, startNanos);
            var extraction = toolExecutor.extractTools(
                    result.output(),
                    type -> allowMutatingProposals || !toolExecutor.isMutatingToolType(type)
            );
            String finalOutput = extraction.cleanedResponse();
            // Strip leftover <fetch> tags
            finalOutput = fetchService.stripFetchTags(finalOutput);

            // Fetch-exhaustion fallback: the model entered the fetch loop but never produced any
            // text outside the <fetch> tags — covers both "hit MAX_FETCH_ROUNDS" and "broke out
            // because the model repeated the same fetch". Give the user a concrete error instead
            // of the silent "(Keine Antwort erhalten)" that the session log shows most often.
            if ((finalOutput == null || finalOutput.isBlank()) && fetchLoopEntered) {
                finalOutput = "Ich konnte die Frage mit bis zu " + MAX_FETCH_ROUNDS
                        + " Daten-Abfragen nicht vollständig auflösen. "
                        + "Bitte formuliere die Frage konkreter oder gib die relevante ID direkt an.";
            }

            AssistantChatResponse parsed = parseResponse(finalOutput);

            // Second call: generate action-button suggestions via the dedicated suggestions-model.
            // Decoupled from the main answer so the primary model stays focused on text quality.
            List<AssistantAction> suggestedActions = fetchSuggestedActions(req, systemPrompt, parsed.message());

            List<AssistantAction> mergedActions = mergeActions(parsed.actions(), suggestedActions);
            List<AssistantAction> filteredActions = filterActions(mergedActions, allowMutatingProposals);
            broadcaster.broadcastStatus(requestId, "COMPLETED", null, startNanos);
            return new AssistantChatResponse(parsed.message(), filteredActions, model, resolvedProvider)
                    .withPendingTools(extraction.pendingTools());

        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            ErrorKind kind = classifyError(e);
            broadcaster.broadcastStatus(requestId, "ERROR", kind.code(), startNanos);
            return new AssistantChatResponse(kind.userMessage(), List.of());
        }
    }

    /**
     * Calls the CLI and fails fast on a non-zero exit code. Without this check, a crashed
     * CLI process (bad API key, malformed config) would surface its error text as if it were
     * a normal assistant reply.
     */
    private com.automation.service.cli.CliSession.EnrichedCommandResult executeOrFail(HostExecutionRequest request) {
        var result = commandExecutorService.executeOnHost(request);
        if (result.exitCode() != 0) {
            String snippet = result.output() == null ? "" : result.output().strip();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
            throw new WorkflowExecutionException(
                    "Assistant CLI failed (exit " + result.exitCode() + "): " + snippet);
        }
        return result;
    }

    private record ErrorKind(String code, String userMessage) {}

    private ErrorKind classifyError(Throwable e) {
        Throwable root = unwrap(e);
        String msg = root != null && root.getMessage() != null ? root.getMessage() : "";

        if (root instanceof IOException && msg.contains("timed out")) {
            int timeout = hostClaudeConfig.getAssistantChatTimeoutSeconds();
            return new ErrorKind("TIMEOUT",
                    "Die Antwort hat länger als " + timeout + " Sekunden gedauert. " +
                    "Versuche es erneut oder formuliere die Frage kürzer. " +
                    "Den Timeout kannst du in den Einstellungen erhöhen.");
        }
        if (root instanceof SQLTransientConnectionException
                || msg.contains("Connection is not available")) {
            return new ErrorKind("POOL_EXHAUSTED",
                    "Das System ist gerade ausgelastet (Datenbank-Pool belegt). " +
                    "Warte einen Moment und versuche es erneut.");
        }
        // JSON parse failures from tool-call extraction (e.g. Unicode full-width comma in the
        // model's tool-JSON). Previously these surfaced as "Provider nicht konfiguriert",
        // which sent users on a wild goose chase.
        if (root instanceof JsonProcessingException
                || msg.contains("Unexpected close marker")
                || msg.contains("was expecting comma")
                || msg.contains("Unexpected character")) {
            return new ErrorKind("TOOL_CALL_MALFORMED",
                    "Meine Tool-Eingabe war nicht valide (JSON-Fehler). " +
                    "Formuliere die Anfrage einfacher oder ohne Sonderzeichen und versuche es erneut.");
        }
        if (msg.toLowerCase().contains("api key")) {
            return new ErrorKind("PROVIDER_UNAVAILABLE",
                    "Der KI-Provider ist nicht korrekt konfiguriert. " +
                    "Prüfe die Einstellungen (API-Key, Modell).");
        }
        if (msg.contains("Assistant CLI failed (exit ")) {
            // Surface the CLI exit code so debugging doesn't require digging through logs.
            int excerptStart = msg.indexOf("(exit ");
            int excerptEnd = Math.min(msg.length(), excerptStart + 60);
            String excerpt = excerptStart >= 0 ? msg.substring(excerptStart, excerptEnd) : "";
            return new ErrorKind("CLI_NON_ZERO_EXIT",
                    "Der KI-Prozess endete mit einem Fehler " + excerpt + ". " +
                    "Prüfe die API-Konfiguration; Details stehen im Server-Log.");
        }
        return new ErrorKind("UNKNOWN",
                "Entschuldigung, ich konnte deine Anfrage gerade nicht verarbeiten. " +
                "Details stehen im Server-Log — bitte versuche es erneut.");
    }

    private Throwable unwrap(Throwable e) {
        Throwable current = e;
        for (int i = 0; i < 10 && current != null; i++) {
            if (current instanceof WorkflowExecutionException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            if (current.getCause() != null && current.getCause() != current) {
                Throwable cause = current.getCause();
                if (cause instanceof IOException || cause instanceof SQLTransientConnectionException) {
                    return cause;
                }
            }
            return current;
        }
        return e;
    }

    private boolean isAutoFetchAllowed(FetchRequest req) {
        return req != null && AUTO_FETCH_TYPES.contains(req.type());
    }

    private String fetchKey(FetchRequest req) {
        return (req.type() == null ? "" : req.type()) + "|" + (req.id() == null ? "" : req.id());
    }

    /** Threshold at which text outside {@code <fetch>} tags counts as a real answer, not a filler
     *  announcement. 200 chars is comfortably longer than typical "Ich schaue mir X an…" stalls
     *  but short enough that a one-sentence factual answer still counts. */
    private static final int ANSWER_TEXT_MIN_CHARS = 200;

    /** Regex for common announcement openings — catches the stall responses that cost users a
     *  round-trip without delivering content. Anchored at start, case-insensitive. */
    private static final Pattern ANNOUNCEMENT_PATTERN = Pattern.compile(
            "(?is)^\\s*(ich\\s+(schau|hole|lade|pr(ü|u)f|prufe|analysier|check|verifizier)|lade\\s+|einen\\s+moment|moment\\b|kurz\\s+nachladen|sekunde).{0,200}",
            Pattern.CASE_INSENSITIVE);

    /**
     * A response counts as a "substantive answer" (→ stop the fetch loop) when the non-fetch
     * text is long enough to be a real answer OR carries an {@code <actions>} block. Short
     * announcement-style filler ("Ich schaue mir den Run an…") is explicitly NOT substantive,
     * even though it's non-blank — otherwise a stripped {@code <fetch>} would leave the user
     * with nothing but the announcement.
     */
    private boolean hasSubstantiveAnswer(String rawOutput, String nonFetchText) {
        if (nonFetchText == null || nonFetchText.isBlank()) {
            return false;
        }
        if (rawOutput != null && rawOutput.contains("<actions>")) {
            return true;
        }
        String trimmed = nonFetchText.trim();
        if (trimmed.length() >= ANSWER_TEXT_MIN_CHARS) {
            return true;
        }
        // Short text — treat as announcement filler and continue the fetch loop.
        return !ANNOUNCEMENT_PATTERN.matcher(trimmed).find() && trimmed.length() >= 40;
    }

    /**
     * Runs the action-suggestion CLI call with a short timeout; failure or timeout returns an
     * empty list so the chat still delivers its text response. Skipped entirely when suggestions
     * are disabled in settings — saves a CLI slot per chat turn.
     */
    private List<AssistantAction> fetchSuggestedActions(AssistantChatRequest req, String pageContext, String answer) {
        if (actionSuggestionService == null || !actionSuggestionService.isEnabled()) {
            return List.of();
        }
        try {
            return CompletableFuture
                    .supplyAsync(() -> actionSuggestionService.suggest(req, answer, pageContext))
                    .completeOnTimeout(List.of(), 35, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.debug("Action-suggestion call failed; returning empty actions", e);
            return List.of();
        }
    }

    /**
     * Merges inline actions (legacy path — main model may still emit {@code <actions>} tags) with
     * suggestion-model actions. Deduplicated by (type, id) — inline wins on conflict.
     */
    private List<AssistantAction> mergeActions(List<AssistantAction> inline, List<AssistantAction> suggested) {
        if ((inline == null || inline.isEmpty()) && (suggested == null || suggested.isEmpty())) {
            return List.of();
        }
        Map<String, AssistantAction> byKey = new LinkedHashMap<>();
        if (inline != null) {
            for (AssistantAction a : inline) {
                if (a != null && a.type() != null) {
                    byKey.put(dedupeKey(a), a);
                }
            }
        }
        if (suggested != null) {
            for (AssistantAction a : suggested) {
                if (a != null && a.type() != null) {
                    byKey.putIfAbsent(dedupeKey(a), a);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String dedupeKey(AssistantAction a) {
        return a.type() + "|" + (a.id() == null ? "" : a.id());
    }

    private List<AssistantAction> filterActions(List<AssistantAction> actions, boolean allowMutatingProposals) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action != null)
                .filter(action -> allowMutatingProposals || !isMutatingActionType(action.type()))
                .toList();
    }

    private boolean isMutatingActionType(String type) {
        return type != null && MUTATING_ACTION_TYPES.contains(type);
    }

    private boolean hasExplicitMutationIntent(AssistantChatRequest req) {
        String message = req != null ? req.message() : null;
        if (message == null || message.isBlank()) {
            return false;
        }
        if (EXPLICIT_MUTATION_VERB.matcher(message).find()
                && EXPLICIT_MUTATION_TARGET.matcher(message).find()) {
            return true;
        }

        String page = req.context() != null ? req.context().page() : null;
        if (page == null) {
            return false;
        }
        boolean contextualRunPage = "run".equals(page) || "runs".equals(page)
                || "workflow".equals(page) || "workflows".equals(page);
        return contextualRunPage && CONTEXTUAL_RUN_VERB.matcher(message).find();
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private AssistantChatResponse parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AssistantChatResponse("(Keine Antwort erhalten)", List.of());
        }

        List<AssistantAction> actions = Collections.emptyList();
        String message = raw.trim();

        int actionsStart = raw.lastIndexOf("<actions>");
        int actionsEnd = raw.lastIndexOf("</actions>");

        if (actionsStart >= 0 && actionsEnd > actionsStart) {
            String jsonPart = raw.substring(actionsStart + "<actions>".length(), actionsEnd).trim();
            message = raw.substring(0, actionsStart).trim();
            try {
                actions = objectMapper.readValue(jsonPart, new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Could not parse assistant actions JSON: {}", jsonPart, e);
            }
        }

        return new AssistantChatResponse(message, actions);
    }
}
