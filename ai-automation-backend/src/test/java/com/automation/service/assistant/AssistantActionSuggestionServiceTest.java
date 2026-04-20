package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private AssistantActionSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new AssistantActionSuggestionService(
                commandExecutorService, appSettingsRepository, new ObjectMapper());
        // Default: enabled with Haiku model
        lenient().when(appSettingsRepository.get("suggestions-model"))
                .thenReturn(Optional.of("claude-haiku-4-5"));
        lenient().when(appSettingsRepository.get("suggestions-enabled"))
                .thenReturn(Optional.of("true"));
        service.init();
    }

    @Test
    void suggest_parsesValidJsonArray() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":"r-1","name":"Letzter Run","label":"Run öffnen"},
                  {"type":"show_workflow","id":"wf-1","name":"Daily","label":"Öffnen"}
                ]""");

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "Kontext");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).type()).isEqualTo("show_run");
        assertThat(actions.get(1).id()).isEqualTo("wf-1");
    }

    @Test
    void suggest_extractsArrayEvenWithSurroundingText() {
        stubCliOutput("""
                Hier die Actions:
                [{"type":"start_wish","id":null,"name":"Wunsch","label":"Als Wunsch"}]
                Fertig.""");

        var actions = service.suggest(reqWithMessage("Wunsch"), "Antwort", "");

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().type()).isEqualTo("start_wish");
    }

    @Test
    void suggest_limitsToMaxThreeActions() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":"r-1","name":"A","label":"A"},
                  {"type":"show_run","id":"r-2","name":"B","label":"B"},
                  {"type":"show_run","id":"r-3","name":"C","label":"C"},
                  {"type":"show_run","id":"r-4","name":"D","label":"D"},
                  {"type":"show_run","id":"r-5","name":"E","label":"E"}
                ]""");

        var actions = service.suggest(reqWithMessage("Runs"), "Antwort", "");

        assertThat(actions).hasSize(3);
    }

    @Test
    void suggest_filtersUnknownActionTypes() {
        stubCliOutput("""
                [
                  {"type":"teleport","id":"x","name":"Weird","label":"???"},
                  {"type":"show_run","id":"r-1","name":"Real","label":"Open"}
                ]""");

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().type()).isEqualTo("show_run");
    }

    @Test
    void suggest_filtersShowRunWithoutId() {
        stubCliOutput("""
                [
                  {"type":"show_run","id":null,"name":"Halluziniert","label":"x"},
                  {"type":"show_run","id":"","name":"Leer","label":"x"},
                  {"type":"start_wish","id":null,"name":"OK","label":"Wunsch"}
                ]""");

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().type()).isEqualTo("start_wish");
    }

    @Test
    void suggest_returnsEmptyOnUnparseableOutput() {
        stubCliOutput("Sorry, no JSON here.");

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).isEmpty();
    }

    @Test
    void suggest_returnsEmptyOnCliFailure() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenThrow(new RuntimeException("CLI exploded"));

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).isEmpty();
    }

    @Test
    void suggest_returnsEmptyOnNonZeroExitCode() {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("[{\"type\":\"show_run\",\"id\":\"x\"}]", 1, null));

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).isEmpty();
    }

    @Test
    void suggest_skipsWhenDisabled() {
        when(appSettingsRepository.get("suggestions-enabled")).thenReturn(Optional.of("false"));
        service.refreshCache();

        var actions = service.suggest(reqWithMessage("Test"), "Antwort", "");

        assertThat(actions).isEmpty();
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void suggest_skipsWhenAnswerBlank() {
        var actions = service.suggest(reqWithMessage("Test"), "", "");

        assertThat(actions).isEmpty();
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
