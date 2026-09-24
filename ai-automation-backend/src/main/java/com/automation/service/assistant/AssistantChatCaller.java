package com.automation.service.assistant;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The CLI side of the assistant chat: seeds the chat settings at startup, caches the chat model and
 * the base instructions, and makes one interactive, read-only CLI call. {@code AssistantService.chat}
 * stays the orchestrator (context, fetch rounds, tools, suggestions) and goes through {@link #call}
 * for the first answer and for every fetch round. {@code AssistantSettingsListener} refreshes the
 * cached model when the setting changes.
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO a4913fe5); bodies unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantChatCaller {

    /**
     * Read-only tool whitelist for the assistant chat CLI. Edit, Write, MultiEdit, NotebookEdit,
     * Bash, Task, TodoWrite and others are <b>not</b> in this list — the CLI will refuse to call
     * them, so the model cannot mutate files or run shell commands from within the chat flow.
     * File mutations must go through MySuricates workflow blocks instead.
     */
    private static final List<String> CHAT_ALLOWED_TOOLS = List.of("Read", "Grep", "Glob", "WebFetch");

    private static final String DEFAULT_THINKING_PHRASES =
            "{\"de\":[\"Denke nach…\",\"Analysiere…\",\"Überlege kurz…\",\"Einen Moment…\"," +
            "\"Bereite Antwort vor…\",\"Lade Kontext…\",\"Formuliere…\",\"Gleich fertig…\"]," +
            "\"en\":[\"Thinking…\",\"Analyzing…\",\"Just a moment…\",\"Loading context…\"," +
            "\"Preparing answer…\",\"Formulating…\",\"Almost there…\",\"Processing…\"]}";

    private final CommandExecutorService commandExecutorService;
    private final HostClaudeConfig hostClaudeConfig;
    private final AppSettingsRepository appSettingsRepository;

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

    /** Called by {@code AssistantSettingsListener} when {@code assistant-model} changes. */
    public void refreshCachedModel() {
        cachedAssistantModel = appSettingsRepository.get("assistant-model")
                .filter(m -> !m.isBlank()).orElse(null);
        log.info("Assistant model cache refreshed: {}", cachedAssistantModel);
    }

    /** The chat model from {@code assistant-model}; {@code null} leaves the choice to the provider. */
    public String cachedModel() {
        return cachedAssistantModel;
    }

    /** The base instructions from {@code assistant-system-prompt.md}; empty when the resource is missing. */
    public String baseInstructions() {
        return cachedBaseInstructions;
    }

    /** One chat call: builds the interactive read-only request and fails fast on a non-zero exit code. */
    public com.automation.service.cli.CliSession.EnrichedCommandResult call(
            AssistantChatRequest req, String workingDir, String command, String systemPrompt, String model) {
        return executeOrFail(chatCall(req, workingDir, command, systemPrompt, model));
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
}
