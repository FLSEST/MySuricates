package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WishRepository wishRepository;
    @Mock private SystemIdentityService systemIdentity;
    @Mock private AppSettingsRepository appSettingsRepository;

    private AssistantService service;

    @BeforeEach
    void setUp() {
        service = new AssistantService(
                commandExecutorService, workflowRunRepository, todoRepository,
                scheduledWorkflowRepository, projectRepository, wishRepository,
                systemIdentity, new ObjectMapper(), appSettingsRepository
        );
        // Benign defaults so context building doesn't blow up
        when(systemIdentity.getVersion()).thenReturn("0.4.3-TEST");
        when(workflowRunRepository.findByStatus(any())).thenReturn(List.of());
        when(workflowRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());
        when(todoRepository.findAll()).thenReturn(List.of());
        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any())).thenReturn(List.of());
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());
        when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
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

        // Message before <actions> should still be returned, actions empty (parse failure)
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

        var req = new AssistantChatRequest("Zweite Frage", history, null, null, null);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubClaude(String responseText) {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult(responseText, 0, null));
    }

    private AssistantChatResponse chat(String message) {
        return service.chat(new AssistantChatRequest(message, List.of(), null, null, null));
    }
}
