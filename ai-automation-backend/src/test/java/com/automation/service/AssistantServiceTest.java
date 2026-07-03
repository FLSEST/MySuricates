package com.automation.service;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.assistant.AssistantActionSuggestionService;
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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import com.automation.service.assistant.PendingTool;
import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private AssistantContextBuilder contextBuilder;
    @Mock private AssistantFetchService fetchService;
    @Mock private AssistantToolExecutor toolExecutor;
    @Mock private AssistantChatBroadcaster broadcaster;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private AssistantActionSuggestionService actionSuggestionService;

    private AssistantService service;

    private HostClaudeConfig hostClaudeConfig;

    @BeforeEach
    void setUp() {
        hostClaudeConfig = new HostClaudeConfig();
        hostClaudeConfig.setAssistantChatTimeoutSeconds(240);
        service = new AssistantService(
                commandExecutorService, aiProviderRegistry, contextBuilder, fetchService, toolExecutor,
                broadcaster, appSettingsRepository, hostClaudeConfig, new ObjectMapper(), actionSuggestionService
        );
        // Action-suggestion service defaults — disabled unless explicitly stubbed per test
        lenient().when(actionSuggestionService.isEnabled()).thenReturn(false);
        lenient().when(actionSuggestionService.suggest(any(), anyString(), any()))
                .thenReturn(AssistantActionSuggestionService.SuggestionResult.empty());
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
        lenient().when(toolExecutor.extractTools(any(), org.mockito.ArgumentMatchers.<Predicate<String>>any()))
                .thenAnswer(inv -> new AssistantToolExecutor.ExtractionResult(inv.getArgument(0), List.of()));
        lenient().when(toolExecutor.isMutatingToolType(anyString())).thenReturn(true);
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

        var result = chat("Starte den Workflow Daily Report");

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

        var result = chat("Starte den Workflow A");

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

        assertThat(result.message()).contains("Sorry");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_nonZeroExitCode_returnsErrorResponse() {
        // CLI crashes with an API-key error — without exit-code check, the error text
        // would bubble through as if it were a normal assistant reply.
        stubClaude("API key invalid: ANTHROPIC_API_KEY not set or empty", 1);

        var result = chat("Test");

        assertThat(result.message()).doesNotContain("API key invalid");
        assertThat(result.message()).contains("configured");
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
                .contains("240 seconds")
                .contains("settings");
    }

    @Test
    void chat_poolExhaustion_returnsPoolSpecificMessage() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("Failed to execute command on host",
                        new SQLTransientConnectionException(
                                "HikariPool-1 - Connection is not available, request timed out after 30000ms")));

        var result = chat("Test");

        assertThat(result.message())
                .contains("busy");
    }

    @Test
    void chat_timeoutRespectsConfiguredTimeout() {
        hostClaudeConfig.setAssistantChatTimeoutSeconds(500);
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("x",
                        new IOException("Host Claude action timed out after 500 seconds")));

        var result = chat("Test");

        assertThat(result.message()).contains("500 seconds");
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
        when(fetchService.executeFetch(fetchReq)).thenReturn("run r1: COMPLETED");
        when(fetchService.stripFetchTags("<fetch type=\"run_detail\" id=\"r1\"/>Was ist mit dem Run?"))
                .thenReturn("");
        when(fetchService.stripFetchTags("Der Run r1 ist fertig."))
                .thenReturn("Der Run r1 ist fertig.");

        var result = chat("Was ist mit dem Run?");

        assertThat(result.message()).isEqualTo("Der Run r1 ist fertig.");
        // Verify that the second call received the enriched prompt with supplemental + round-0 hint
        verify(commandExecutorService).executeOnHost(argThat(req ->
                req.command() != null
                        && req.command().contains("run r1: COMPLETED")
                        && req.command().contains("round 1")
                        && req.command().contains("original question now")
        ));
    }

    @Test
    void chat_responseWithSubstantiveTextAndFetchDoesNotAutoFetch() {
        // Long text (>= 200 chars) next to a fetch tag is treated as a complete answer — no
        // second round, even though the model also emitted a speculative fetch.
        String longAnswer = "Der Run ist abgeschlossen und hat alle fünf Schritte erfolgreich durchlaufen. "
                + "Die Ausgabe enthält keine Fehler, und das Gate-Status-Feld steht auf grün. "
                + "Der Workflow kann jetzt wie geplant weitergeführt werden.";
        String raw = longAnswer + " <fetch type=\"run_detail\" id=\"r1\"/>";
        stubClaude(raw);
        when(fetchService.stripFetchTags(raw)).thenReturn(longAnswer);

        var result = chat("Was ist mit dem Run?");

        assertThat(result.message()).isEqualTo(longAnswer);
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
        verify(fetchService, never()).executeFetch(any());
    }

    @Test
    void chat_announcementTextWithFetchStillAutoFetches() {
        // The observed regression: model emits "Ich schaue mir den Run an." + <fetch>. Old logic
        // saw non-blank text and treated it as the final answer — user got just the announcement.
        // New logic detects announcement-style filler and still runs the fetch loop.
        stubClaudeSequence(
                "Ich schaue mir die Details des Runs an. <fetch type=\"run_detail\" id=\"r1\"/>",
                "Der Run r1 ist FAILED — Step 0 mit Exit 137 (OOM)."
        );
        var fetchReq = new AssistantFetchService.FetchRequest("run_detail", "r1");
        when(fetchService.parseFetchRequests("Ich schaue mir die Details des Runs an. <fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn(List.of(fetchReq));
        when(fetchService.executeFetch(fetchReq)).thenReturn("run r1: FAILED, step 0 OOM");
        when(fetchService.stripFetchTags("Ich schaue mir die Details des Runs an. <fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn("Ich schaue mir die Details des Runs an.");
        when(fetchService.stripFetchTags("Der Run r1 ist FAILED — Step 0 mit Exit 137 (OOM)."))
                .thenReturn("Der Run r1 ist FAILED — Step 0 mit Exit 137 (OOM).");

        var result = chat("Was ist mit dem Run?");

        assertThat(result.message()).isEqualTo("Der Run r1 ist FAILED — Step 0 mit Exit 137 (OOM).");
        verify(fetchService).executeFetch(fetchReq);
    }

    @Test
    void chat_responseWithActionsAndFetchDoesNotAutoFetch() {
        // An <actions> block means the model already produced a complete structured answer —
        // honor it even if the visible text is short.
        String raw = "Ja. <actions>[{\"type\":\"show_run\",\"id\":\"r-9\",\"name\":\"Last Run\",\"label\":\"Öffnen\"}]</actions> <fetch type=\"run_detail\" id=\"r2\"/>";
        stubClaude(raw);
        when(fetchService.stripFetchTags(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s == null ? null : s.replaceAll("<fetch[^>]*/>", "").trim();
        });

        var result = chat("Run?");

        assertThat(result.actions()).hasSize(1);
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
        verify(fetchService, never()).executeFetch(any());
    }

    @Test
    void chat_disallowedFetchTypeDoesNotAutoFetch() {
        stubClaude("<fetch type=\"project_context\" id=\"p1\"/>");
        var fetchReq = new AssistantFetchService.FetchRequest("project_context", "p1");
        when(fetchService.parseFetchRequests("<fetch type=\"project_context\" id=\"p1\"/>"))
                .thenReturn(List.of(fetchReq));
        when(fetchService.stripFetchTags("<fetch type=\"project_context\" id=\"p1\"/>"))
                .thenReturn("");

        var result = chat("Analysiere das Projekt");

        assertThat(result.message()).isEqualTo("(No response received)");
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
        verify(fetchService, never()).executeFetch(any());
    }

    @Test
    void chat_multiRoundFetchChainsUpToThreeRoundsAndAccumulatesDetails() {
        // Round 0: model wants run_detail. Round 1: still fetch-only, wants step_output.
        // Round 2: finally produces a text answer. We should see three CLI calls total
        // (initial + 2 follow-ups) and the third call must contain both supplemental blocks.
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>",
                "<fetch type=\"step_output\" id=\"r1/0\"/>",
                "Der Run r1 ist fertig — Step 0 hat 1.2GB geschrieben."
        );
        var firstFetch = new AssistantFetchService.FetchRequest("run_detail", "r1");
        var secondFetch = new AssistantFetchService.FetchRequest("step_output", "r1/0");
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn(List.of(firstFetch));
        when(fetchService.parseFetchRequests("<fetch type=\"step_output\" id=\"r1/0\"/>"))
                .thenReturn(List.of(secondFetch));
        when(fetchService.executeFetch(firstFetch)).thenReturn("run r1: COMPLETED");
        when(fetchService.executeFetch(secondFetch)).thenReturn("step 0: 1.2GB output");
        when(fetchService.stripFetchTags("<fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn("");
        when(fetchService.stripFetchTags("<fetch type=\"step_output\" id=\"r1/0\"/>"))
                .thenReturn("");
        when(fetchService.stripFetchTags("Der Run r1 ist fertig — Step 0 hat 1.2GB geschrieben."))
                .thenReturn("Der Run r1 ist fertig — Step 0 hat 1.2GB geschrieben.");

        var result = chat("Was ist mit dem Run?");

        assertThat(result.message()).isEqualTo("Der Run r1 ist fertig — Step 0 hat 1.2GB geschrieben.");
        // Third call receives both supplemental blocks and the round-2 hint
        verify(commandExecutorService).executeOnHost(argThat(req ->
                req.command() != null
                        && req.command().contains("run r1: COMPLETED")
                        && req.command().contains("step 0: 1.2GB output")
                        && req.command().contains("round 2")
        ));
    }

    @Test
    void chat_duplicateFetchAcrossRoundsIsSkippedAndBreaksLoop() {
        // Model repeats the same fetch in round 1 — the duplicate filter drops it, leaving
        // no fetches → loop breaks. No second enrichment is sent.
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>",
                "<fetch type=\"run_detail\" id=\"r1\"/>"
        );
        var fetchReq = new AssistantFetchService.FetchRequest("run_detail", "r1");
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn(List.of(fetchReq));
        when(fetchService.executeFetch(fetchReq)).thenReturn("run r1: COMPLETED");
        when(fetchService.stripFetchTags("<fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn("");

        var result = chat("Was ist mit dem Run?");

        // Only one fetch is executed, not two
        verify(fetchService, org.mockito.Mockito.times(1)).executeFetch(fetchReq);
        // Response is empty text after 2 rounds (initial + 1 follow-up) → fetch-exhaustion fallback
        assertThat(result.message()).contains("data fetches").contains("specifically");
    }

    @Test
    void chat_exhaustedFetchRoundsProducesFallbackMessage() {
        // Model keeps emitting different fetches but never delivers a text answer.
        // After MAX_FETCH_ROUNDS the user should see a concrete fallback, not "(No response received)".
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>",
                "<fetch type=\"run_detail\" id=\"r2\"/>",
                "<fetch type=\"run_detail\" id=\"r3\"/>",
                "<fetch type=\"run_detail\" id=\"r4\"/>"
        );
        when(fetchService.parseFetchRequests(any()))
                .thenAnswer(inv -> {
                    String raw = inv.getArgument(0);
                    if (raw.contains("r1")) return List.of(new AssistantFetchService.FetchRequest("run_detail", "r1"));
                    if (raw.contains("r2")) return List.of(new AssistantFetchService.FetchRequest("run_detail", "r2"));
                    if (raw.contains("r3")) return List.of(new AssistantFetchService.FetchRequest("run_detail", "r3"));
                    if (raw.contains("r4")) return List.of(new AssistantFetchService.FetchRequest("run_detail", "r4"));
                    return List.of();
                });
        when(fetchService.executeFetch(any())).thenReturn("run data");
        when(fetchService.stripFetchTags(any())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s.startsWith("<fetch") ? "" : s;
        });

        var result = chat("Analysiere die letzten Runs");

        assertThat(result.message())
                .contains("data fetches")
                .contains("specifically");
    }

    @Test
    void chat_jsonParseErrorClassifiesAsToolCallMalformed() {
        // Full-width Unicode comma in tool JSON — previously misreported as "provider nicht konfiguriert".
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new WorkflowExecutionException("tool parse failed",
                        new com.fasterxml.jackson.core.JsonParseException(
                                null, "Unexpected character ('，' (code 65292)): was expecting comma")));

        var result = chat("Erstelle einen Workflow");

        assertThat(result.message())
                .contains("JSON error")
                .doesNotContain("configured");
    }

    @Test
    void chat_blankSupplementalBreaksLoop() {
        // Fetches are returned, but executeFetch yields only blanks → supplemental.isBlank() → break.
        stubClaude("<fetch type=\"unknown\" id=\"x\"/>Antwort trotzdem.");
        when(fetchService.stripFetchTags("<fetch type=\"unknown\" id=\"x\"/>Antwort trotzdem."))
                .thenReturn("Antwort trotzdem.");

        var result = chat("Q");

        assertThat(result.message()).isEqualTo("Antwort trotzdem.");
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

    @Test
    void chat_stripsMutatingToolProposalWithoutExplicitIntent() {
        when(toolExecutor.extractTools(any(), argThat((Predicate<String> predicate) -> !predicate.test("create_todo"))))
                .thenReturn(new AssistantToolExecutor.ExtractionResult("Analyse ohne Karte.", List.of()));
        when(fetchService.stripFetchTags("Analyse ohne Karte.")).thenReturn("Analyse ohne Karte.");
        stubClaude("Ich schlage vor, ein TODO anzulegen. <tool type=\"create_todo\">{\"title\":\"x\"}</tool>");

        var result = chat("Das waere eher ein Feature.");

        assertThat(result.message()).isEqualTo("Analyse ohne Karte.");
        assertThat(result.pendingTools()).isNullOrEmpty();
        verify(toolExecutor).extractTools(any(), argThat((Predicate<String> predicate) -> !predicate.test("create_todo")));
    }

    @Test
    void chat_keepsMutatingToolProposalForExplicitIntent() {
        var pending = List.of(new com.automation.service.assistant.PendingTool(
                "p1", "create_todo", "TODO anlegen", "{}", java.time.Instant.now().plusSeconds(60)));
        when(toolExecutor.extractTools(any(), argThat((Predicate<String> predicate) -> predicate.test("create_todo"))))
                .thenReturn(new AssistantToolExecutor.ExtractionResult("Ich schlage das vor.", pending));
        when(fetchService.stripFetchTags("Ich schlage das vor.")).thenReturn("Ich schlage das vor.");
        stubClaude("<tool type=\"create_todo\">{\"title\":\"x\"}</tool>");

        var result = chat("Bitte lege ein TODO an.");

        assertThat(result.message()).isEqualTo("Ich schlage das vor.");
        assertThat(result.pendingTools()).hasSize(1);
        verify(toolExecutor).extractTools(any(), argThat((Predicate<String> predicate) -> predicate.test("create_todo")));
    }

    @Test
    void chat_filtersRunWorkflowActionWithoutExplicitIntent() {
        stubClaude("""
                Ich kann das starten.
                <actions>[{"type":"run_workflow","id":"wf-42","name":"Daily Report","label":"Jetzt starten"}]</actions>""");

        var result = chat("Was waere sinnvoll?");

        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_keepsRunWorkflowActionForExplicitIntent() {
        stubClaude("""
                Ich kann das starten.
                <actions>[{"type":"run_workflow","id":"wf-42","name":"Daily Report","label":"Jetzt starten"}]</actions>""");

        var result = service.chat(new AssistantChatRequest(
                "Bitte starte ihn nochmal.",
                List.of(),
                new AssistantChatRequest.PageContext("workflow", "wf-42", null, "wf-42", null, "/workflows/wf-42"),
                null,
                null,
                null
        ));

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("run_workflow");
    }

    // ── Action suggestions (suggestions-model decoupling) ───────────────────

    @Test
    void chat_mergesSuggestedActionsIntoResponse() {
        stubClaude("Hier ist der Status.");
        when(fetchService.stripFetchTags("Hier ist der Status.")).thenReturn("Hier ist der Status.");
        var suggested = new AssistantChatResponse.AssistantAction(
                "show_run", "r-9", "Letzter Run", "Run öffnen");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), eq("Hier ist der Status."), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(
                        List.of(suggested), List.of()));

        var result = chat("Wie lief der letzte Run?");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("show_run");
        assertThat(result.actions().getFirst().id()).isEqualTo("r-9");
    }

    @Test
    void chat_skipsActionSuggestionsWhenDisabled() {
        stubClaude("Antwort.");
        when(fetchService.stripFetchTags("Antwort.")).thenReturn("Antwort.");
        when(actionSuggestionService.isEnabled()).thenReturn(false);

        var result = chat("Frage");

        assertThat(result.actions()).isEmpty();
        verify(actionSuggestionService, never()).suggest(any(), anyString(), any());
    }

    @Test
    void chat_inlineActionsWinOverSuggestedOnConflict() {
        // Main model emitted its own <actions> for wf-1; suggestion model proposes wf-1 too + wf-2.
        // Inline entry wins; wf-2 is added on top.
        stubClaude("""
                Text.
                <actions>[{"type":"show_workflow","id":"wf-1","name":"Inline","label":"Inline öffnen"}]</actions>""");
        when(fetchService.stripFetchTags(any())).thenAnswer(inv -> inv.getArgument(0));
        var suggestedConflict = new AssistantChatResponse.AssistantAction(
                "show_workflow", "wf-1", "Suggested", "Suggested öffnen");
        var suggestedNew = new AssistantChatResponse.AssistantAction(
                "show_workflow", "wf-2", "Extra", "Extra öffnen");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(
                        List.of(suggestedConflict, suggestedNew), List.of()));

        var result = chat("Welche Workflows gibt es?");

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).id()).isEqualTo("wf-1");
        assertThat(result.actions().get(0).name()).isEqualTo("Inline");
        assertThat(result.actions().get(1).id()).isEqualTo("wf-2");
    }

    @Test
    void chat_actionSuggestionFailureStillReturnsTextAnswer() {
        stubClaude("Antwort trotzdem.");
        when(fetchService.stripFetchTags("Antwort trotzdem.")).thenReturn("Antwort trotzdem.");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenThrow(new RuntimeException("CLI boom"));

        var result = chat("Frage");

        assertThat(result.message()).isEqualTo("Antwort trotzdem.");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void chat_suggestedRunWorkflowFilteredWithoutExplicitIntent() {
        stubClaude("Hier sind die Optionen.");
        when(fetchService.stripFetchTags(any())).thenAnswer(inv -> inv.getArgument(0));
        var runAction = new AssistantChatResponse.AssistantAction(
                "run_workflow", "wf-42", "Daily Report", "Jetzt starten");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(
                        List.of(runAction), List.of()));

        var result = chat("Welche Workflows gibt es?");

        assertThat(result.actions()).isEmpty();
    }

    // ── Broadcaster status sequence ──────────────────────────────────────────

    @Test
    void chat_successPath_broadcastsCorrectStatusSequenceInOrder() {
        stubClaude("Antwort.");
        when(fetchService.stripFetchTags("Antwort.")).thenReturn("Antwort.");

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-1"));

        var inOrder = org.mockito.Mockito.inOrder(broadcaster);
        inOrder.verify(broadcaster).broadcastStatus(eq("req-1"), eq("BUILDING_CONTEXT"), any(), anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-1"), eq("THINKING"), any(), anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-1"), eq("EXECUTING_TOOLS"), any(), anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-1"), eq("COMPLETED"), any(), anyLong());
    }

    @Test
    void chat_errorPath_broadcastsErrorStatus() {
        when(commandExecutorService.executeOnHost(any()))
                .thenThrow(new RuntimeException("boom"));

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-err"));

        verify(broadcaster).broadcastStatus(eq("req-err"), eq("ERROR"), anyString(), anyLong());
    }

    @Test
    void chat_timeoutError_broadcastsTimeoutCode() {
        when(commandExecutorService.executeOnHost(any()))
                .thenThrow(new WorkflowExecutionException("x",
                        new java.io.IOException("Host Claude action timed out after 240 seconds")));

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-timeout"));

        verify(broadcaster).broadcastStatus(eq("req-timeout"), eq("ERROR"), eq("TIMEOUT"), anyLong());
    }

    @Test
    void chat_cliNonZeroExit_broadcastsCliNonZeroExitCode() {
        stubClaude("Fatal: segmentation fault in subprocess", 1);

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-exit"));

        verify(broadcaster).broadcastStatus(eq("req-exit"), eq("ERROR"), eq("CLI_NON_ZERO_EXIT"), anyLong());
    }

    @Test
    void chat_fetchRound_broadcastsFetchingDataStatusPerRound() {
        stubClaudeSequence(
                "<fetch type=\"run_detail\" id=\"r1\"/>",
                "Der Run r1 ist fertig."
        );
        var fetchReq = new AssistantFetchService.FetchRequest("run_detail", "r1");
        when(fetchService.parseFetchRequests("<fetch type=\"run_detail\" id=\"r1\"/>"))
                .thenReturn(List.of(fetchReq));
        when(fetchService.executeFetch(fetchReq)).thenReturn("run data");
        when(fetchService.stripFetchTags("<fetch type=\"run_detail\" id=\"r1\"/>")).thenReturn("");
        when(fetchService.stripFetchTags("Der Run r1 ist fertig.")).thenReturn("Der Run r1 ist fertig.");

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-fetch"));

        verify(broadcaster).broadcastStatus(eq("req-fetch"), eq("FETCHING_DATA"), anyString(), anyLong());
        verify(broadcaster, times(2)).broadcastStatus(eq("req-fetch"), eq("THINKING"), any(), anyLong());
    }

    // ── PendingTool merge ─────────────────────────────────────────────────────

    @Test
    void chat_mergesPendingToolsInlineBeforeSuggested() {
        var inlineTool = new PendingTool("p-inline", "create_todo", "Inline TODO", "{}", Instant.now().plusSeconds(60));
        var suggestedTool = new PendingTool("p-suggested", "create_workflow", "Suggested Workflow", "{}", Instant.now().plusSeconds(60));
        stubClaude("Vorschlag.");
        when(fetchService.stripFetchTags("Vorschlag.")).thenReturn("Vorschlag.");
        when(toolExecutor.extractTools(any(), any()))
                .thenReturn(new AssistantToolExecutor.ExtractionResult("Vorschlag.", List.of(inlineTool)));
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(
                        List.of(), List.of(suggestedTool)));

        var result = chat("Lege bitte ein TODO an.");

        assertThat(result.pendingTools()).hasSize(2);
        assertThat(result.pendingTools().get(0).id()).isEqualTo("p-inline");
        assertThat(result.pendingTools().get(1).id()).isEqualTo("p-suggested");
    }

    @Test
    void chat_onlyInlinePendingTools_returnedCorrectly() {
        var inlineTool = new PendingTool("p1", "create_todo", "TODO", "{}", Instant.now().plusSeconds(60));
        stubClaude("Vorschlag.");
        when(fetchService.stripFetchTags("Vorschlag.")).thenReturn("Vorschlag.");
        when(toolExecutor.extractTools(any(), any()))
                .thenReturn(new AssistantToolExecutor.ExtractionResult("Vorschlag.", List.of(inlineTool)));

        var result = chat("Lege ein TODO an.");

        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().get(0).id()).isEqualTo("p1");
    }

    @Test
    void chat_onlySuggestedPendingTools_returnedCorrectly() {
        var suggestedTool = new PendingTool("p-sug", "create_workflow", "Workflow anlegen", "{}", Instant.now().plusSeconds(60));
        stubClaude("Antwort.");
        when(fetchService.stripFetchTags("Antwort.")).thenReturn("Antwort.");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(
                        List.of(), List.of(suggestedTool)));

        var result = chat("Erstelle einen Workflow.");

        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().get(0).id()).isEqualTo("p-sug");
    }

    @Test
    void chat_noPendingTools_returnsEmpty() {
        stubClaude("Normale Antwort.");
        when(fetchService.stripFetchTags("Normale Antwort.")).thenReturn("Normale Antwort.");

        var result = chat("Frage");

        assertThat(result.pendingTools()).isNullOrEmpty();
    }

    // ── refreshCachedModel ────────────────────────────────────────────────────

    @Test
    void refreshCachedModel_updatesModelUsedInNextChatCall() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-opus-4-8"));
        service.refreshCachedModel();
        stubClaude("ok");

        service.chat(new AssistantChatRequest("Test", List.of(), null, null, null, null));

        verify(commandExecutorService).executeOnHost(argThat(req -> "claude-opus-4-8".equals(req.model())));
    }

    @Test
    void refreshCachedModel_withBlankSetting_setsModelToNull() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("  "));
        service.refreshCachedModel();
        stubClaude("ok");

        service.chat(new AssistantChatRequest("Test", List.of(), null, null, null, null));

        verify(commandExecutorService).executeOnHost(argThat(req -> req.model() == null));
    }

    @Test
    void refreshCachedModel_withEmptySetting_setsModelToNull() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.empty());
        service.refreshCachedModel();
        stubClaude("ok");

        service.chat(new AssistantChatRequest("Test", List.of(), null, null, null, null));

        verify(commandExecutorService).executeOnHost(argThat(req -> req.model() == null));
    }

    // ── MAX_FETCHES_PER_ROUND capping ─────────────────────────────────────────

    @Test
    void chat_moreThanThreeFetchesInOneRound_capsAtThree() {
        String firstResponse = "<fetch type=\"run_detail\" id=\"r1\"/>"
                + "<fetch type=\"run_detail\" id=\"r2\"/>"
                + "<fetch type=\"run_detail\" id=\"r3\"/>"
                + "<fetch type=\"run_detail\" id=\"r4\"/>";
        stubClaudeSequence(firstResponse, "Fertig.");
        var f1 = new FetchRequest("run_detail", "r1");
        var f2 = new FetchRequest("run_detail", "r2");
        var f3 = new FetchRequest("run_detail", "r3");
        var f4 = new FetchRequest("run_detail", "r4");
        when(fetchService.parseFetchRequests(firstResponse)).thenReturn(List.of(f1, f2, f3, f4));
        when(fetchService.executeFetch(any())).thenReturn("data");
        when(fetchService.stripFetchTags(firstResponse)).thenReturn("");
        when(fetchService.stripFetchTags("Fertig.")).thenReturn("Fertig.");

        chat("Los gehts.");

        verify(fetchService, times(3)).executeFetch(any());
    }

    // ── Cached model & settings (seedSettings / refreshCachedModel) ─────────

    @Test
    void refreshCachedModel_updatesModelUsedInChat() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-opus-4-6"));
        service.refreshCachedModel();
        stubClaude("Antwort");

        var result = chat("Frage");

        assertThat(result.message()).isEqualTo("Antwort");
        verify(commandExecutorService).executeOnHost(argThat(req ->
                "claude-opus-4-6".equals(req.model())));
    }

    @Test
    void refreshCachedModel_blankSetting_clearsCachedModel() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("   "));
        service.refreshCachedModel();
        stubClaude("Antwort");

        var result = chat("Frage");

        assertThat(result.message()).isEqualTo("Antwort");
        verify(commandExecutorService).executeOnHost(argThat(req -> req.model() == null));
    }

    @Test
    void seedSettings_seedsDefaultThinkingPhrases_whenMissing() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        service.seedSettings();

        verify(appSettingsRepository).set(eq("assistant-thinking-phrases"),
                argThat(v -> v.startsWith("{\"de\"") && v.contains("\"en\"")));
    }

    @Test
    void seedSettings_migratesLegacyArrayThinkingPhrases() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.of("[\"Denke nach…\"]"));
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        service.seedSettings();

        verify(appSettingsRepository).set(eq("assistant-thinking-phrases"), argThat(v -> v.startsWith("{")));
    }

    @Test
    void seedSettings_seedsDefaultModel_whenBlank() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.of("{\"de\":[]}"));
        when(appSettingsRepository.get("assistant-model"))
                .thenReturn(Optional.of(" "), Optional.of("claude-sonnet-4-6"));

        service.seedSettings();

        verify(appSettingsRepository).set("assistant-model", "claude-sonnet-4-6");
    }

    // ── Chat context building ────────────────────────────────────────────────

    @Test
    void chat_passesSystemPromptWorkingDirTimeoutAndPriorityToCli() {
        when(contextBuilder.buildSystemPrompt(any(), any(), eq("en"))).thenReturn("SYS-PROMPT");
        when(contextBuilder.resolveWorkingDir(any())).thenReturn("/work/dir");
        stubClaude("Answer");

        var result = service.chat(new AssistantChatRequest(
                "Question", List.of(), null, null, "en", null, null));

        assertThat(result.message()).isEqualTo("Answer");
        verify(commandExecutorService).executeOnHost(argThat(req ->
                "SYS-PROMPT".equals(req.systemPrompt())
                        && "/work/dir".equals(req.projectPath())
                        && req.priority()
                        && Integer.valueOf(240).equals(req.timeoutSeconds())));
    }

    @Test
    void chat_passesLanguageToSystemPromptBuilder() {
        stubClaude("Answer");

        service.chat(new AssistantChatRequest("Question", List.of(), null, null, "en", null, null));

        verify(contextBuilder).buildSystemPrompt(any(), any(), eq("en"));
    }

    @Test
    void chat_passesProviderIdToCliAndResolvesIt() {
        stubClaude("Antwort");

        service.chat(new AssistantChatRequest("Frage", List.of(), null, "CODEX", null, null));

        verify(commandExecutorService).executeOnHost(argThat(req -> "CODEX".equals(req.providerId())));
        verify(aiProviderRegistry).resolve("CODEX");
    }

    // ── Status broadcasting ──────────────────────────────────────────────────

    @Test
    void chat_broadcastsLifecyclePhasesInOrder() {
        stubClaude("Antwort");

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-42"));

        var inOrder = org.mockito.Mockito.inOrder(broadcaster);
        inOrder.verify(broadcaster).broadcastStatus(eq("req-42"), eq("BUILDING_CONTEXT"), any(), org.mockito.ArgumentMatchers.anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-42"), eq("THINKING"), any(), org.mockito.ArgumentMatchers.anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-42"), eq("EXECUTING_TOOLS"), any(), org.mockito.ArgumentMatchers.anyLong());
        inOrder.verify(broadcaster).broadcastStatus(eq("req-42"), eq("COMPLETED"), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void chat_errorBroadcastsErrorPhaseWithCode() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        service.chat(new AssistantChatRequest("Frage", List.of(), null, null, null, "req-err"));

        verify(broadcaster).broadcastStatus(eq("req-err"), eq("ERROR"), eq("UNKNOWN"), org.mockito.ArgumentMatchers.anyLong());
    }

    // ── Fetch dispatch: limits & invalid requests ───────────────────────────

    @Test
    void chat_limitsFetchDispatchToThreePerRound() {
        stubClaudeSequence(
                "<fetch-multi/>",
                "Fertig: Antwort auf Basis der ersten drei geladenen Details."
        );
        var f1 = new FetchRequest("run_detail", "r1");
        var f2 = new FetchRequest("run_detail", "r2");
        var f3 = new FetchRequest("run_detail", "r3");
        var f4 = new FetchRequest("run_detail", "r4");
        when(fetchService.parseFetchRequests("<fetch-multi/>")).thenReturn(List.of(f1, f2, f3, f4));
        when(fetchService.executeFetch(any())).thenReturn("data");
        when(fetchService.stripFetchTags("<fetch-multi/>")).thenReturn("");
        when(fetchService.stripFetchTags("Fertig: Antwort auf Basis der ersten drei geladenen Details."))
                .thenReturn("Fertig: Antwort auf Basis der ersten drei geladenen Details.");

        var result = chat("Analysiere die Runs");

        assertThat(result.message()).isEqualTo("Fertig: Antwort auf Basis der ersten drei geladenen Details.");
        verify(fetchService, org.mockito.Mockito.times(3)).executeFetch(any());
        verify(fetchService, never()).executeFetch(f4);
    }

    @Test
    void chat_invalidFetchRequests_unknownType_isIgnored() {
        stubClaude("<fetch type=\"kaputt\"/>");
        var unknownType = new FetchRequest("delete_everything", "x2");
        when(fetchService.parseFetchRequests("<fetch type=\"kaputt\"/>"))
                .thenReturn(List.of(unknownType));
        when(fetchService.stripFetchTags("<fetch type=\"kaputt\"/>")).thenReturn("");

        var result = chat("Q");

        assertThat(result.message()).isEqualTo("(No response received)");
        verify(fetchService, never()).executeFetch(any());
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void chat_shortFactualAnswerNextToFetchStopsLoop() {
        String raw = "Der Run r1 ist fehlgeschlagen (Exit 137). <fetch type=\"run_detail\" id=\"r1\"/>";
        stubClaude(raw);
        when(fetchService.stripFetchTags(raw)).thenReturn("Der Run r1 ist fehlgeschlagen (Exit 137).");

        var result = chat("Status?");

        assertThat(result.message()).isEqualTo("Der Run r1 ist fehlgeschlagen (Exit 137).");
        verify(fetchService, never()).executeFetch(any());
        verify(commandExecutorService, org.mockito.Mockito.times(1))
                .executeOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void chat_nonZeroExitWithoutApiKeyHint_surfacesExitCode() {
        stubClaude("segfault: core dumped", 137);

        var result = chat("Test");

        assertThat(result.message()).contains("exit 137");
    }

    // ── Pending-tool merging ─────────────────────────────────────────────────

    @Test
    void chat_mergesInlinePendingToolsBeforeSuggestedOnes() {
        var inline = new com.automation.service.assistant.PendingTool(
                "p-inline", "create_todo", "Inline-Vorschlag", "{}", java.time.Instant.now().plusSeconds(60));
        var suggested = new com.automation.service.assistant.PendingTool(
                "p-sugg", "create_todo", "Suggestion-Vorschlag", "{}", java.time.Instant.now().plusSeconds(60));
        when(toolExecutor.extractTools(any(), org.mockito.ArgumentMatchers.<Predicate<String>>any()))
                .thenReturn(new AssistantToolExecutor.ExtractionResult("Antwort mit Vorschlag.", List.of(inline)));
        when(fetchService.stripFetchTags("Antwort mit Vorschlag.")).thenReturn("Antwort mit Vorschlag.");
        stubClaude("<tool type=\"create_todo\">{}</tool>");
        when(actionSuggestionService.isEnabled()).thenReturn(true);
        when(actionSuggestionService.suggest(any(), any(), any()))
                .thenReturn(new AssistantActionSuggestionService.SuggestionResult(List.of(), List.of(suggested)));

        var result = chat("Bitte lege ein Todo an.");

        assertThat(result.pendingTools()).containsExactly(inline, suggested);
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
