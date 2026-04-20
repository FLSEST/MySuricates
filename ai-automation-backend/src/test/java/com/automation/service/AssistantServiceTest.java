package com.automation.service;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.assistant.AssistantChatBroadcaster;
import com.automation.service.assistant.AssistantContextBuilder;
import com.automation.service.assistant.AssistantFetchService;
import com.automation.service.assistant.AssistantFetchService.FetchRequest;
import com.automation.service.assistant.AssistantToolExecutor;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private AssistantContextBuilder contextBuilder;
    @Mock private AssistantFetchService fetchService;
    @Mock private AssistantToolExecutor toolExecutor;
    @Mock private AssistantChatBroadcaster broadcaster;
    @Mock private AppSettingsRepository appSettingsRepository;

    private AssistantService service;

    private HostClaudeConfig hostClaudeConfig;

    @BeforeEach
    void setUp() {
        hostClaudeConfig = new HostClaudeConfig();
        hostClaudeConfig.setAssistantChatTimeoutSeconds(240);
        service = new AssistantService(
                commandExecutorService, aiProviderRegistry, contextBuilder, fetchService, toolExecutor,
                broadcaster, appSettingsRepository, hostClaudeConfig, new ObjectMapper()
        );
        // Provider registry default — resolve to CLAUDE
        AiProvider claudeProvider = mock(AiProvider.class);
        lenient().when(claudeProvider.getId()).thenReturn("CLAUDE");
        lenient().when(aiProviderRegistry.resolve(any())).thenReturn(claudeProvider);
        // Context builder defaults (lenient — not all tests call chat())
        lenient().when(contextBuilder.buildSystemPrompt(any(), any())).thenReturn("System prompt");
        lenient().when(contextBuilder.buildPrompt(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(contextBuilder.resolveWorkingDir(any())).thenReturn("/tmp");
        // Fetch service defaults — no fetches
        lenient().when(fetchService.parseFetchRequests(any())).thenReturn(List.of());
        // Tool executor — no pending tools, pass cleaned response through unchanged
        lenient().when(toolExecutor.extractTools(any()))
                .thenAnswer(inv -> new AssistantToolExecutor.ExtractionResult(inv.getArgument(0), List.of()));
        // Fetch strip — pass through
        lenient().when(fetchService.stripFetchTags(any())).thenAnswer(inv -> inv.getArgument(0));
        // Seed settings as @PostConstruct would in prod — populates cachedBaseInstructions + cachedAssistantModel
        lenient().when(appSettingsRepository.get(anyString())).thenReturn(Optional.of("claude-sonnet-4-6"));
        service.seedSettings();
    }

    // ── Base instructions loading ────────────────────────────────────────────

    @Test
    void seedSettings_loadsBaseInstructionsFromClasspath() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.of("dummy"));

        service.seedSettings();

        // After seedSettings, the chat should work with base instructions loaded
        stubClaude("Test response");
        var result = chat("Test");
        assertThat(result.message()).isEqualTo("Test response");
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    @Test
    void chat_plainResponse_returnsMsgAndEmptyActions() {
        stubClaude("Ich bin MySuricates. Heute liefen 5 Workflows.");

        var result = chat("Was bist du?");

        assertThat(result.message()).isEqualTo("Ich bin MySuricates. Heute liefen 5 Workflows.");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_responseWithActions_parsesActionsCorrectly() {
        stubClaude("""
                Ja, ich starte den Workflow für dich.
                <actions>[{"type":"run_workflow","id":"wf-42","name":"Daily Report","label":"Jetzt starten"}]</actions>""");

        var result = chat("Starte Daily Report");

        assertThat(result.message()).isEqualTo("Ja, ich starte den Workflow für dich.");
        assertThat(result.actions()).hasSize(1);
        AssistantChatResponse.AssistantAction action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo("run_workflow");
        assertThat(action.id()).isEqualTo("wf-42");
        assertThat(action.name()).isEqualTo("Daily Report");
        assertThat(action.label()).isEqualTo("Jetzt starten");
    }

    @Test
    void chat_responseWithMultipleActions_parsesAll() {
        stubClaude("""
                Du hast zwei Optionen:
                <actions>[{"type":"run_workflow","id":"wf-1","name":"Workflow A","label":"A starten"},{"type":"show_workflow","id":"wf-2","name":"Workflow B","label":"B anzeigen"}]</actions>""");

        var result = chat("Was kann ich tun?");

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).type()).isEqualTo("run_workflow");
        assertThat(result.actions().get(1).type()).isEqualTo("show_workflow");
    }

    @Test
    void chat_malformedActionsJson_returnsEmptyActions() {
        stubClaude("Hier die Aktion:\n<actions>KEIN_GUELTIGES_JSON</actions>");

        var result = chat("Test");

        assertThat(result.message()).contains("Hier die Aktion:");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_emptyClaudeResponse_returnsFallback() {
        stubClaude("");

        var result = chat("Test");

        assertThat(result.message()).isNotBlank();
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_nullClaudeResponse_returnsFallback() {
        stubClaude(null);

        var result = chat("Test");

        assertThat(result.message()).isNotBlank();
    }

    // ── History formatting ────────────────────────────────────────────────────

    @Test
    void chat_withHistory_includesHistoryInPrompt() {
        stubClaude("Antwort mit Kontext");
        var history = List.of(
                new AssistantChatRequest.MessageEntry("user", "Erste Frage"),
                new AssistantChatRequest.MessageEntry("assistant", "Erste Antwort")
        );

        var req = new AssistantChatRequest("Zweite Frage", history, null, null, null, null);
        var result = service.chat(req);

        assertThat(result.message()).isEqualTo("Antwort mit Kontext");
    }

    @Test
    void chat_emptyHistory_doesNotCrash() {
        stubClaude("OK");

        var result = chat("Frage");

        assertThat(result.message()).isEqualTo("OK");
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void chat_commandExecutorThrows_returnsFallbackMessage() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("Simulated AI failure"));

        var result = chat("Test");

        assertThat(result.message()).contains("Entschuldigung");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_nonZeroExitCode_returnsErrorResponse() {
        // CLI crashes with an API-key error — without exit-code check, the error text
        // would bubble through as if it were a normal assistant reply.
        stubClaude("API key invalid: ANTHROPIC_API_KEY not set or empty", 1);

        var result = chat("Test");

        assertThat(result.message()).doesNotContain("API key invalid");
        assertThat(result.message()).contains("konfiguriert");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_zeroExitCode_passesThrough() {
        stubClaude("Normale Antwort", 0);

        var result = chat("Test");

        assertThat(result.message()).isEqualTo("Normale Antwort");
    }

    @Test
    void chat_timeoutException_returnsTimeoutSpecificMessage() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("Failed to execute command on host (project: /tmp)",
                        new IOException("Host Claude action timed out after 240 seconds")));

        var result = chat("Test");

        assertThat(result.message())
                .contains("240 Sekunden")
                .contains("Einstellungen");
    }

    @Test
    void chat_poolExhaustion_returnsPoolSpecificMessage() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("Failed to execute command on host",
                        new SQLTransientConnectionException(
                                "HikariPool-1 - Connection is not available, request timed out after 30000ms")));

        var result = chat("Test");

        assertThat(result.message())
                .contains("ausgelastet");
    }

    @Test
    void chat_timeoutRespectsConfiguredTimeout() {
        hostClaudeConfig.setAssistantChatTimeoutSeconds(500);
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("x",
                        new IOException("Host Claude action timed out after 500 seconds")));

        var result = chat("Test");

        assertThat(result.message()).contains("500 Sekunden");
    }

    // ── Fetch loop ────────────────────────────────────────────────────────────

    @Test
    void chat_fetchRoundExecutesSupplementalLoadAndCallsCliTwice() {
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>Was ist mit dem Run?",
                "Der Run r1 ist fertig."
        );
        var fetchReq = new AssistantFetchService.FetchRequest("run_detail", "r1");
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r1\"/>Was ist mit dem Run?"))
                .thenReturn(List.of(fetchReq));
        when(fetchService.parseFetchRequests("Der Run r1 ist fertig.")).thenReturn(List.of());
        when(fetchService.executeFetch(fetchReq)).thenReturn("run r1: COMPLETED");

        var result = chat("Was ist mit dem Run?");

        assertThat(result.message()).isEqualTo("Der Run r1 ist fertig.");
        // Verify that the second call received the enriched prompt with supplemental + round-0 hint
        verify(commandExecutorService).executeOnHost(argThat(req ->
                req.command() != null
                        && req.command().contains("run r1: COMPLETED")
                        && req.command().contains("Runde 1")
                        && req.command().contains("ursprüngliche Frage jetzt")
        ));
    }

    @Test
    void chat_secondFetchRoundHitsLimitHint() {
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>",
                "<fetch type=\"run_detail\" id=\"r2\"/>",
                "Finale Antwort."
        );
        var f1 = new AssistantFetchService.FetchRequest("run_detail", "r1");
        var f2 = new AssistantFetchService.FetchRequest("run_detail", "r2");
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r1\"/>")).thenReturn(List.of(f1));
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r2\"/>")).thenReturn(List.of(f2));
        // After 2 rounds, the loop exits without parsing "Finale Antwort." again
        when(fetchService.executeFetch(f1)).thenReturn("detail r1");
        when(fetchService.executeFetch(f2)).thenReturn("detail r2");

        var result = chat("Query");

        assertThat(result.message()).isEqualTo("Finale Antwort.");
        // Round-1 CLI call must include the limit hint ("Keine weiteren Fetches möglich.")
        verify(commandExecutorService).executeOnHost(argThat(req ->
                req.command() != null
                        && req.command().contains("detail r2")
                        && req.command().contains("Runde 2")
                        && req.command().contains("Keine weiteren Fetches möglich")
        ));
    }

    @Test
    void chat_blankSupplementalBreaksLoop() {
        // Fetches are returned, but executeFetch yields only blanks → supplemental.isBlank() → break.
        stubClaude("<fetch type=\"unknown\" id=\"x\"/>Antwort trotzdem.");
        var fetchReq = new AssistantFetchService.FetchRequest("unknown", "x");
        when(fetchService.parseFetchRequests(any())).thenReturn(List.of(fetchReq));
        when(fetchService.executeFetch(fetchReq)).thenReturn("   ");

        var result = chat("Q");

        assertThat(result.message()).isEqualTo("<fetch type=\"unknown\" id=\"x\"/>Antwort trotzdem.");
        // Exactly one CLI call — no enriched second round after blank supplemental break
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void chat_stripsLeftoverFetchTags() {
        // Fetch-loop is a no-op (no parsed fetches), but the raw output still contains a <fetch> tag
        // that was not stripped by the loop. stripFetchTags should remove it before returning.
        stubClaude("Hier: <fetch type=\"docs\" id=\"architektur\"/> ist gemeint.");
        when(fetchService.stripFetchTags("Hier: <fetch type=\"docs\" id=\"architektur\"/> ist gemeint."))
                .thenReturn("Hier:  ist gemeint.");

        var result = chat("Q");

        assertThat(result.message()).doesNotContain("<fetch");
        assertThat(result.message()).isEqualTo("Hier:  ist gemeint.");
    }

    @Test
    void chat_enforcesReadOnlyToolWhitelist() {
        // Hard-gate: the chat CLI call must run with a strict tool whitelist. Edit/Write/Bash must
        // not be reachable even if the model tries — the guarantee lives in the HostExecutionRequest.
        stubClaude("ok");

        chat("Hello");

        verify(commandExecutorService).executeOnHost(argThat(req ->
                req.allowedTools() != null
                        && req.allowedTools().containsAll(List.of("Read", "Grep", "Glob", "WebFetch"))
                        && !req.allowedTools().contains("Edit")
                        && !req.allowedTools().contains("Write")
                        && !req.allowedTools().contains("Bash")
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubClaude(String responseText) {
        stubClaude(responseText, 0);
    }

    private void stubClaude(String responseText, int exitCode) {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult(responseText, exitCode, null));
    }

    private void stubClaudeSequence(String first, String... rest) {
        var firstResult = new CliSession.EnrichedCommandResult(first, 0, null);
        CliSession.EnrichedCommandResult[] restResults = new CliSession.EnrichedCommandResult[rest.length];
        for (int i = 0; i < rest.length; i++) {
            restResults[i] = new CliSession.EnrichedCommandResult(rest[i], 0, null);
        }
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(firstResult, restResults);
    }

    private AssistantChatResponse chat(String message) {
        return service.chat(new AssistantChatRequest(message, List.of(), null, null, null, null));
    }
}
