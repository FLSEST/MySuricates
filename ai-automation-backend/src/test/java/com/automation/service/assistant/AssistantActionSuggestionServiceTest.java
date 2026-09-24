package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.dto.PendingTool;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantActionSuggestionServiceTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private PendingToolRegistry pendingToolRegistry;
    @Mock private PendingToolDescriber describer;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;

    private AssistantActionSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new AssistantActionSuggestionService(
                commandExecutorService, appSettingsRepository, new ObjectMapper(),
                pendingToolRegistry, describer, workflowRepository, workflowRunRepository);
        // Default: enabled with Sonnet model
        lenient().when(appSettingsRepository.get("suggestions-model"))
                .thenReturn(Optional.of("claude-sonnet-4-6"));
        lenient().when(appSettingsRepository.get("suggestions-enabled"))
                .thenReturn(Optional.of("true"));
        // Repositories: empty by default — individual tests override when needed.
        lenient().when(workflowRepository.findAll()).thenReturn(List.of());
        Page<com.automation.entity.WorkflowRun> emptyRuns = new PageImpl<>(List.of());
        lenient().when(workflowRunRepository.findAll(any(Pageable.class))).thenReturn(emptyRuns);
        // PendingToolRegistry: register echoes back a synthetic PendingTool.
        lenient().when(pendingToolRegistry.register(any(), any(PendingToolLabel.class), any())).thenAnswer(inv ->
                new PendingTool("pt-" + inv.<String>getArgument(0),
                        inv.getArgument(0), inv.<PendingToolLabel>getArgument(1).description(), inv.getArgument(2),
                        java.time.Instant.now().plusSeconds(60)));
        // Describer: simple stringification.
        lenient().when(describer.describe(any(), any())).thenAnswer(inv ->
                PendingToolLabel.of(inv.<String>getArgument(0) + ": " + inv.<String>getArgument(1)));
        service.init();
    }

    @Test
    void suggest_parsesValidJsonArrayAsNavigationalActions() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":"r-1","name":"Letzter Run","label":"Run öffnen"},
                  {"type":"show_workflow","id":"wf-1","name":"Daily","label":"Öffnen"}
                ]""");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "Kontext");

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).type()).isEqualTo("show_run");
        assertThat(result.actions().get(1).id()).isEqualTo("wf-1");
        assertThat(result.pendingTools()).isEmpty();
    }

    @Test
    void suggest_extractsArrayEvenWithSurroundingText() {
        stubCliOutput("""
                Hier die Actions:
                [{"type":"start_wish","id":null,"name":"Wunsch","label":"Als Wunsch"}]
                Fertig.""");

        var result = service.suggest(reqWithMessage("Wunsch"), "Antwort", "");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("start_wish");
    }

    @Test
    void suggest_limitsToMaxFourActions() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":"r-1","name":"A","label":"A"},
                  {"type":"show_run","id":"r-2","name":"B","label":"B"},
                  {"type":"show_run","id":"r-3","name":"C","label":"C"},
                  {"type":"show_run","id":"r-4","name":"D","label":"D"},
                  {"type":"show_run","id":"r-5","name":"E","label":"E"}
                ]""");

        var result = service.suggest(reqWithMessage("Runs"), "Antwort", "");

        assertThat(result.actions()).hasSize(4);
    }

    @Test
    void suggest_filtersUnknownActionTypes() {
        stubCliOutput("""
                [
                  {"type":"teleport","id":"x","name":"Weird","label":"???"},
                  {"type":"show_run","id":"r-1","name":"Real","label":"Open"}
                ]""");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("show_run");
    }

    @Test
    void suggest_filtersShowRunWithoutId() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":null,"name":"Halluziniert","label":"x"},
                  {"type":"show_run","id":"","name":"Leer","label":"x"},
                  {"type":"start_wish","id":null,"name":"OK","label":"Wunsch"}
                ]""");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("start_wish");
    }

    @Test
    void suggest_returnsEmptyOnUnparseableOutput() {
        stubCliOutput("Sorry, no JSON here.");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).isEmpty();
        assertThat(result.pendingTools()).isEmpty();
    }

    @Test
    void suggest_returnsEmptyOnCliFailure() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("CLI exploded"));

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).isEmpty();
        assertThat(result.pendingTools()).isEmpty();
    }

    @Test
    void suggest_returnsEmptyOnNonZeroExitCode() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("[{\"type\":\"show_run\",\"id\":\"x\"}]", 1, null));

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).isEmpty();
    }

    @Test
    void suggest_skipsWhenDisabled() {
        when(appSettingsRepository.get("suggestions-enabled")).thenReturn(Optional.of("false"));
        service.refreshCache();

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.actions()).isEmpty();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void suggest_skipsWhenAnswerBlank() {
        var result = service.suggest(reqWithMessage("Test"), "", "");

        assertThat(result.actions()).isEmpty();
    }

    @Test
    void isEnabled_reflectsSettingAfterRefresh() {
        assertThat(service.isEnabled()).isTrue();

        when(appSettingsRepository.get("suggestions-enabled")).thenReturn(Optional.of("false"));
        service.refreshCache();
        assertThat(service.isEnabled()).isFalse();

        when(appSettingsRepository.get("suggestions-enabled")).thenReturn(Optional.of("true"));
        service.refreshCache();
        assertThat(service.isEnabled()).isTrue();
    }

    // ── Mutating proposals (propose_*) ──────────────────────────────────────

    @Test
    void suggest_convertsProposeCreateTodoIntoPendingTool() {
        stubCliOutput("""
                [{"type":"propose_create_todo","id":null,"name":"Bug fixen",
                  "label":"TODO anlegen",
                  "payload":{"title":"Bug fixen","priority":"HIGH","tags":["backend","bug"]}}]""");

        var result = service.suggest(reqWithMessage("Hier ist ein Bug"), "Antwort", "");

        assertThat(result.actions()).isEmpty();
        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().getFirst().type()).isEqualTo("create_todo");
    }

    @Test
    void suggest_convertsProposeCancelRunIntoPendingTool() {
        stubCliOutput("""
                [{"type":"propose_cancel_run","id":"run-42","name":"Hängender Run",
                  "label":"Run abbrechen"}]""");

        var result = service.suggest(reqWithMessage("Soll ich abbrechen?"), "Antwort", "");

        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().getFirst().type()).isEqualTo("cancel_run");
    }

    @Test
    void suggest_convertsProposeRerunWorkflowIntoStartWorkflowPendingTool() {
        stubCliOutput("""
                [{"type":"propose_rerun_workflow","id":"wf-7","name":"Daily Report",
                  "label":"Workflow neu starten"}]""");

        var result = service.suggest(reqWithMessage("Nochmal?"), "Antwort", "");

        assertThat(result.pendingTools()).hasSize(1);
        // Maps onto the existing start_workflow tool type for reuse of the executor.
        assertThat(result.pendingTools().getFirst().type()).isEqualTo("start_workflow");
    }

    @Test
    void suggest_convertsProposeUpdateTodoStatusIntoPendingTool() {
        stubCliOutput("""
                [{"type":"propose_update_todo_status","id":"todo-1","name":"Test-TODO",
                  "label":"Auf DONE setzen",
                  "payload":{"status":"DONE"}}]""");

        var result = service.suggest(reqWithMessage("ist erledigt"), "Antwort", "");

        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().getFirst().type()).isEqualTo("update_todo_status");
    }

    @Test
    void suggest_dropsProposeCancelRunWithoutRunId() {
        stubCliOutput("""
                [{"type":"propose_cancel_run","id":null,"name":"Kein ID","label":"x"}]""");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.pendingTools()).isEmpty();
    }

    @Test
    void suggest_dropsProposeUpdateTodoStatusWithoutStatus() {
        stubCliOutput("""
                [{"type":"propose_update_todo_status","id":"todo-1","name":"x","label":"x",
                  "payload":{}}]""");

        var result = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(result.pendingTools()).isEmpty();
    }

    @Test
    void suggest_mixesNavigationalAndProposalsInSameResponse() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":"r-1","name":"Run","label":"öffnen"},
                  {"type":"propose_create_todo","id":null,"name":"TODO Titel",
                   "label":"TODO anlegen","payload":{"title":"TODO Titel","priority":"MEDIUM"}}
                ]""");

        var result = service.suggest(reqWithMessage("?"), "Antwort", "");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo("show_run");
        assertThat(result.pendingTools()).hasSize(1);
        assertThat(result.pendingTools().getFirst().type()).isEqualTo("create_todo");
    }

    @Test
    void init_seedsSuggestionsModelWhenEmpty() {
        when(appSettingsRepository.get("suggestions-model")).thenReturn(Optional.empty());

        service.init();

        // Verify a write call against the repository to set the default
        org.mockito.Mockito.verify(appSettingsRepository).set(
                org.mockito.ArgumentMatchers.eq("suggestions-model"),
                org.mockito.ArgumentMatchers.eq("sonnet"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCliOutput(String output) {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult(output, 0, null));
    }

    private AssistantChatRequest reqWithMessage(String msg) {
        return new AssistantChatRequest(msg, List.of(),
                new AssistantChatRequest.PageContext("dashboard", null, null, null, null, null),
                null, null, null);
    }

    @SuppressWarnings("unused")
    private AssistantAction action(String type, String id) {
        return new AssistantAction(type, id, "n", "l");
    }
}
