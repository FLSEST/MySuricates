package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantContextBuilder;
import com.automation.service.assistant.AssistantFetchService;
import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
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

    private static final int TIMEOUT_SECONDS = 120;
    private static final String DEFAULT_THINKING_PHRASES =
            "{\"de\":[\"Denke nach…\",\"Analysiere…\",\"Überlege kurz…\",\"Einen Moment…\"," +
            "\"Bereite Antwort vor…\",\"Lade Kontext…\",\"Formuliere…\",\"Gleich fertig…\"]," +
            "\"en\":[\"Thinking…\",\"Analyzing…\",\"Just a moment…\",\"Loading context…\"," +
            "\"Preparing answer…\",\"Formulating…\",\"Almost there…\",\"Processing…\"]}";

    private final CommandExecutorService commandExecutorService;
    private final AssistantContextBuilder contextBuilder;
    private final AssistantFetchService fetchService;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantChatBroadcaster broadcaster;
    private final AppSettingsRepository appSettingsRepository;
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

            broadcaster.broadcastStatus(requestId, "THINKING", null, startNanos);
            var result = commandExecutorService.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(workingDir)
                            .command(prompt)
                            .systemPrompt(systemPrompt)
                            .providerId(req.provider())
                            .model(model)
                            .timeoutSeconds(TIMEOUT_SECONDS)
                            .priority(true)
                            .build()
            );

            // Dynamic fetch loop (max 2 rounds)
            for (int fetchRound = 0; fetchRound < 2; fetchRound++) {
                List<FetchRequest> fetches = fetchService.parseFetchRequests(result.output());
                if (fetches.isEmpty()) break;
                broadcaster.broadcastStatus(requestId, "FETCHING_DATA", "Runde " + (fetchRound + 1), startNanos);
                String supplemental = fetches.stream()
                        .map(fetchService::executeFetch)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("\n"));
                if (supplemental.isBlank()) break;
                String hint = fetchRound == 0
                        ? "[Bitte beantworte die ursprüngliche Frage jetzt mit diesen Details.]"
                        : "[Bitte beantworte die Frage JETZT mit den verfügbaren Daten. Keine weiteren Fetches möglich.]";
                String enrichedPrompt = prompt
                        + "\n\n---\nNachgeladene Details (Runde " + (fetchRound + 1) + "):\n" + supplemental
                        + "\n\n" + hint;
                broadcaster.broadcastStatus(requestId, "THINKING", "Runde " + (fetchRound + 1), startNanos);
                result = commandExecutorService.executeOnHost(
                        HostExecutionRequest.builder()
                                .projectPath(workingDir)
                                .command(enrichedPrompt)
                                .systemPrompt(systemPrompt)
                                .providerId(req.provider())
                                .model(model)
                                .timeoutSeconds(TIMEOUT_SECONDS)
                                .priority(true)
                                .build()
                );
            }

            // Execute tools in final response
            broadcaster.broadcastStatus(requestId, "EXECUTING_TOOLS", null, startNanos);
            String finalOutput = toolExecutor.executeTools(result.output());
            // Strip leftover <fetch> tags
            finalOutput = fetchService.stripFetchTags(finalOutput);

            AssistantChatResponse parsed = parseResponse(finalOutput);
            broadcaster.broadcastStatus(requestId, "COMPLETED", null, startNanos);
            return new AssistantChatResponse(parsed.message(), parsed.actions(), model);

        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            broadcaster.broadcastStatus(requestId, "ERROR", e.getMessage(), startNanos);
            return new AssistantChatResponse(
                    "Entschuldigung, ich konnte deine Anfrage gerade nicht verarbeiten. " +
                    "Bitte versuche es erneut oder prüfe ob der KI-Provider konfiguriert ist.",
                    List.of()
            );
        }
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
