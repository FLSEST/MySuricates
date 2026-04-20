package com.automation.service;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantContextBuilder;
import com.automation.service.assistant.AssistantFetchService;
import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
    private static final Set<String> AUTO_FETCH_TYPES = Set.of(
            "run_detail",
            "step_output",
            "wish_detail",
            "workflow_runs",
            "workflow_detail",
            "workflow-inputs",
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

            // Demand-driven auto-fetch: at most one follow-up call, only for a single safe fetch
            for (int fetchRound = 0; fetchRound < 1; fetchRound++) {
                String rawOutput = result.output();
                String nonFetchText = fetchService.stripFetchTags(rawOutput);
                boolean fetchOnlyResponse = nonFetchText == null || nonFetchText.isBlank();
                if (!fetchOnlyResponse) break;

                List<FetchRequest> fetches = fetchService.parseFetchRequests(rawOutput).stream()
                        .filter(this::isAutoFetchAllowed)
                        .limit(1)
                        .toList();
                if (fetches.isEmpty()) break;

                broadcaster.broadcastStatus(requestId, "FETCHING_DATA", "Runde " + (fetchRound + 1), startNanos);
                String supplemental = fetches.stream()
                        .map(fetchService::executeFetch)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                if (supplemental.isBlank()) break;
                String enrichedPrompt = prompt
                        + "\n\n---\nNachgeladene Details (Runde " + (fetchRound + 1) + "):\n" + supplemental
                        + "\n\n[Bitte beantworte die ursprüngliche Frage jetzt mit diesen Details.]";
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

            AssistantChatResponse parsed = parseResponse(finalOutput);
            List<AssistantAction> filteredActions = filterActions(parsed.actions(), allowMutatingProposals);
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
        if (msg.toLowerCase().contains("provider") || msg.toLowerCase().contains("api key")) {
            return new ErrorKind("PROVIDER_UNAVAILABLE",
                    "Der KI-Provider ist nicht korrekt konfiguriert. " +
                    "Prüfe die Einstellungen (API-Key, Modell).");
        }
        return new ErrorKind("UNKNOWN",
                "Entschuldigung, ich konnte deine Anfrage gerade nicht verarbeiten. " +
                "Bitte versuche es erneut oder prüfe ob der KI-Provider konfiguriert ist.");
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
