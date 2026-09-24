package com.automation.service;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.assistant.AssistantActionMerger;
import com.automation.service.assistant.AssistantActionSuggestionService;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantContextBuilder;
import com.automation.service.assistant.AssistantErrorClassifier;
import com.automation.service.assistant.AssistantErrorClassifier.ErrorKind;
import com.automation.service.assistant.AssistantFetchPolicy;
import com.automation.service.assistant.AssistantFetchService;
import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import com.automation.service.assistant.AssistantMutationGate;
import com.automation.service.assistant.AssistantResponseParser;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the assistant chat flow.
 * Delegates to:
 * - {@link AssistantContextBuilder} for system/user prompt construction
 * - {@link AssistantFetchService} for dynamic fetch resolution
 * - {@link AssistantToolExecutor} for tool execution (create_todo, create_workflow, etc.)
 * - {@link AssistantFetchPolicy} / {@link AssistantMutationGate} / {@link AssistantActionMerger}
 *   for the pure decisions inside the loop
 * - {@link AssistantResponseParser} for splitting text from the legacy {@code <actions>} block
 * - {@link AssistantErrorClassifier} for the user-facing error message on failure
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
    private final AssistantActionSuggestionService actionSuggestionService;
    private final AssistantErrorClassifier errorClassifier;
    private final AssistantResponseParser responseParser;

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
            appSettingsRepository.set("assistant-model", "sonnet");
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

    /** Called by {@link AssistantSettingsListener} when {@code assistant-model} changes. */
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
            String systemPrompt = contextBuilder.buildSystemPrompt(req.context(), cachedBaseInstructions, req.language());
            String prompt = contextBuilder.buildPrompt(req.history(), req.message());
            String workingDir = contextBuilder.resolveWorkingDir(req.context());
            String model = cachedAssistantModel;
            String resolvedProvider = aiProviderRegistry.resolve(req.provider()).getId();
            boolean allowMutatingProposals = AssistantMutationGate.hasExplicitMutationIntent(req);

            broadcaster.broadcastStatus(requestId, "THINKING", null, startNanos);
            var result = executeOrFail(chatCall(req, workingDir, prompt, systemPrompt, model));

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
                if (AssistantFetchPolicy.hasSubstantiveAnswer(rawOutput, nonFetchText)) break;

                List<FetchRequest> fetches = fetchService.parseFetchRequests(rawOutput).stream()
                        .filter(AssistantFetchPolicy::isAutoFetchAllowed)
                        .filter(f -> seenFetches.add(AssistantFetchPolicy.fetchKey(f)))
                        .limit(MAX_FETCHES_PER_ROUND)
                        .toList();
                if (fetches.isEmpty()) break;
                fetchLoopEntered = true;

                broadcaster.broadcastStatus(requestId, "FETCHING_DATA", "Round " + (fetchRound + 1), startNanos);
                String supplemental = fetches.stream()
                        .map(fetchService::executeFetch)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                log.debug("Assistant fetch round {} — {} fetch(es): {}",
                        fetchRound + 1, fetches.size(),
                        fetches.stream().map(AssistantFetchPolicy::fetchKey).toList());
                if (supplemental.isBlank()) break;

                accumulatedSupplemental = accumulatedSupplemental.isBlank()
                        ? supplemental
                        : accumulatedSupplemental + "\n\n" + supplemental;
                String enrichedPrompt = prompt
                        + "\n\n---\nFetched details (round " + (fetchRound + 1) + "):\n"
                        + accumulatedSupplemental
                        + "\n\n[Please answer the original question now using these details. "
                        + "If something truly essential is still missing, name it in 1 sentence and then make "
                        + "at most one more specific <fetch>.]";
                broadcaster.broadcastStatus(requestId, "THINKING", "Round " + (fetchRound + 1), startNanos);
                result = executeOrFail(chatCall(req, workingDir, enrichedPrompt, systemPrompt, model));
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
                finalOutput = "I could not fully resolve the question with up to " + MAX_FETCH_ROUNDS
                        + " data fetches. "
                        + "Please phrase the question more specifically or provide the relevant ID directly.";
            }

            AssistantChatResponse parsed = responseParser.parse(finalOutput);

            // Second call: generate action-button suggestions via the dedicated suggestions-model.
            // Decoupled from the main answer so the primary model stays focused on text quality.
            // The suggestion service may also return mutating-proposal pending-tools (already
            // registered in the PendingToolRegistry) which we merge with whatever the main model
            // emitted via <tool> tags.
            AssistantActionSuggestionService.SuggestionResult suggestion =
                    fetchSuggestedActions(req, systemPrompt, parsed.message());

            List<AssistantAction> mergedActions = AssistantActionMerger.mergeActions(parsed.actions(), suggestion.actions());
            List<AssistantAction> filteredActions = AssistantMutationGate.filterActions(mergedActions, allowMutatingProposals);
            List<com.automation.service.assistant.PendingTool> mergedPendingTools =
                    AssistantActionMerger.mergePendingTools(extraction.pendingTools(), suggestion.pendingTools());
            broadcaster.broadcastStatus(requestId, "COMPLETED", null, startNanos);
            return new AssistantChatResponse(parsed.message(), filteredActions, model, resolvedProvider)
                    .withPendingTools(mergedPendingTools);

        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            ErrorKind kind = errorClassifier.classify(e);
            broadcaster.broadcastStatus(requestId, "ERROR", kind.code(), startNanos);
            return new AssistantChatResponse(kind.userMessage(), List.of());
        }
    }

    /** One interactive, read-only CLI call of the chat flow; identical for the first call and every fetch round. */
    private HostExecutionRequest chatCall(AssistantChatRequest req, String workingDir, String command,
                                         String systemPrompt, String model) {
        return HostExecutionRequest.builder()
                .projectPath(workingDir)
                .command(command)
                .systemPrompt(systemPrompt)
                .providerId(req.provider())
                .model(model)
                .timeoutSeconds(hostClaudeConfig.getAssistantChatTimeoutSeconds())
                .priority(true)
                .allowedTools(CHAT_ALLOWED_TOOLS)
                .build();
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

    /**
     * Runs the action-suggestion CLI call with a short timeout; failure or timeout returns an
     * empty result so the chat still delivers its text response. Skipped entirely when
     * suggestions are disabled in settings — saves a CLI slot per chat turn.
     */
    private AssistantActionSuggestionService.SuggestionResult fetchSuggestedActions(
            AssistantChatRequest req, String pageContext, String answer) {
        if (actionSuggestionService == null || !actionSuggestionService.isEnabled()) {
            return AssistantActionSuggestionService.SuggestionResult.empty();
        }
        try {
            return CompletableFuture
                    .supplyAsync(() -> actionSuggestionService.suggest(req, answer, pageContext))
                    .completeOnTimeout(AssistantActionSuggestionService.SuggestionResult.empty(),
                            35, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.debug("Action-suggestion call failed; returning empty actions", e);
            return AssistantActionSuggestionService.SuggestionResult.empty();
        }
    }

}
