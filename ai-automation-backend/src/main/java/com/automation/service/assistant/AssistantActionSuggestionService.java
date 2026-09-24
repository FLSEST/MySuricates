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
            You generate action suggestions for the MySuricates chat — both navigation and
            proactive mutation proposals. The user should see options for what they could do
            next. When something useful comes up (bug detected → TODO; FAILED run → re-run;
            hanging run → cancel), propose it proactively — even if the user doesn't
            explicitly ask for it. Prefer 1-2 truly fitting suggestions over 4 generic ones.

            Respond EXCLUSIVELY with a JSON array (max 4 entries) or [].
            No explanations, no markdown, no ```-fence, just the array.

            Navigation types (user clicks → page opens):
              show_run       {"type":"show_run","id":"<run-id>","name":"Run name","label":"Open run"}
              show_workflow  {"type":"show_workflow","id":"<workflow-id>","name":"Workflow name","label":"Open workflow"}
              start_wish     {"type":"start_wish","id":null,"name":"<wish text>","label":"Phrase as a wish"}
              ask_choice     {"type":"ask_choice","id":null,"name":"Choice","label":"…","options":["A","B"]}
              run_workflow   {"type":"run_workflow","id":"<workflow-id>","name":"Workflow name","label":"Start now"}

            Proactive mutation proposals (user gets a Confirm/Reject card):
              propose_create_todo
                {"type":"propose_create_todo","id":null,"name":"<TODO title>","label":"Create TODO",
                 "payload":{"title":"<TODO title>","priority":"HIGH|MEDIUM|LOW","tags":["area","type"],"description":"<short context optional>"}}
              propose_cancel_run
                {"type":"propose_cancel_run","id":"<run-id>","name":"<run label>","label":"Cancel run"}
              propose_rerun_workflow
                {"type":"propose_rerun_workflow","id":"<workflow-id>","name":"<workflow name>","label":"Restart workflow"}
              propose_update_todo_status
                {"type":"propose_update_todo_status","id":"<todo-id>","name":"<TODO title>","label":"Set to DONE",
                 "payload":{"status":"DONE|OPEN|IN_PROGRESS|BACKLOG"}}

            Examples of useful proactive thinking:
            - User on a run page with FAILED status: `propose_rerun_workflow` (workflow-id from the context)
              and possibly `propose_create_todo` with a bug diagnosis as the title.
            - User writes a bug observation or feature idea: `propose_create_todo` with
              a fitting title + tags. Tags convention: `[area, type]` e.g. `["backend","bug"]`,
              `["frontend","ux"]`, `["assistant","feature"]`.
            - Run page with status RUNNING and runtime > 10 min in a normally-short workflow:
              `propose_cancel_run` with the run-id of the page.
            - User says "it's done" or similar on a TODO page: `propose_update_todo_status` (status=DONE)
              with the todo-id from the context.

            Rules:
            - Use only IDs from the provided context (page context, workflow index,
              recent runs). Do not invent IDs.
            - `payload` must be pure ASCII JSON — no Unicode fullwidth commas (`，`).
            - For ambiguous references → `ask_choice` instead of guessing.
            - `run_workflow` (direct start without confirm) ONLY when the user explicitly says
              "start/run/go". Otherwise prefer `propose_rerun_workflow`.
            - Nothing fits → return []. Better empty than bad.
            """;

    private final CommandExecutorService commandExecutorService;
    private final AppSettingsRepository appSettingsRepository;
    private final ObjectMapper objectMapper;
    private final PendingToolRegistry pendingToolRegistry;
    private final PendingToolDescriber describer;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;

    /** Cached suggestions model — loaded at startup, refreshed on settings change. */
    private volatile String cachedSuggestionsModel = null;

    /** Cached on/off flag for suggestion generation (shared with page-suggestions). */
    private volatile boolean cachedSuggestionsEnabled = true;

    /** Default model for the suggestion call. Sonnet is fast enough for the 30s window and
     *  produces noticeably better proposals than the CLI's local default (typically Haiku).
     *  Per memory rule: never Haiku for chat/assistant. */
    private static final String DEFAULT_SUGGESTIONS_MODEL = "sonnet";

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
            PendingToolLabel label = describer.describe(toolType, payloadJson);
            return pendingToolRegistry.register(toolType, label, payloadJson);
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
        sb.append("Page context (use only IDs and names from this section):\n");
        if (pageContext != null && !pageContext.isBlank()) {
            String trimmed = pageContext.length() > 6000
                    ? pageContext.substring(0, 6000) + "\n…[truncated]"
                    : pageContext;
            sb.append(trimmed).append("\n\n");
        } else {
            sb.append("(no additional context)\n\n");
        }

        sb.append("Current page path: ");
        if (req != null && req.context() != null && req.context().page() != null) {
            sb.append("/").append(req.context().page());
        } else {
            sb.append("(unknown)");
        }
        sb.append("\n\n");

        // Page entity: explicit so the suggestion model can refer to "this run"/"this workflow"
        // by id without re-deriving it from the page-context blob.
        if (req != null && req.context() != null && req.context().entityId() != null) {
            sb.append("Current entity ID on this page: ").append(req.context().entityId()).append("\n\n");
        }

        // Workflow index — names + IDs only — so the model can propose run_workflow /
        // propose_rerun_workflow with a real ID without it appearing in the answer text.
        appendWorkflowIndex(sb);

        // Recent runs — gives the model run-ids for propose_cancel_run / propose_rerun_workflow.
        appendRecentRuns(sb);

        sb.append("User question:\n");
        sb.append(req != null && req.message() != null ? req.message() : "").append("\n\n");

        sb.append("Assistant's answer:\n");
        sb.append(assistantAnswer).append("\n\n");

        sb.append("Now generate the JSON array with at most ")
                .append(MAX_ACTIONS)
                .append(" action suggestions (or [] if nothing fits).");
        return sb.toString();
    }

    /** Compact list of (id, name) for top workflows by recently-updated, capped to keep
     *  the prompt small. Skipped silently on repository errors. */
    private void appendWorkflowIndex(StringBuilder sb) {
        try {
            List<Workflow> all = workflowRepository.findAll();
            if (all == null || all.isEmpty()) return;
            sb.append("Workflow index (use these IDs if applicable):\n");
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
            sb.append("Recent runs (use these IDs for cancel/rerun):\n");
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
