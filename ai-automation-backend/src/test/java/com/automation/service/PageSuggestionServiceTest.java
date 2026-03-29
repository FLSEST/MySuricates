package com.automation.service;

import com.automation.entity.AssistantSession;
import com.automation.entity.PageSuggestion;
import com.automation.entity.WorkflowMemory;
import com.automation.repository.AssistantSessionRepository;
import com.automation.repository.PageSuggestionRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PageSuggestionServiceTest {

    @Mock
    private AssistantSessionRepository sessionRepository;

    @Mock
    private PageSuggestionRepository suggestionRepository;

    @Mock
    private WorkflowMemoryRepository workflowMemoryRepository;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private CommandExecutorService commandExecutorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PageSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new PageSuggestionService(
                sessionRepository, suggestionRepository, workflowMemoryRepository,
                appSettingsRepository, commandExecutorService, objectMapper);
    }

    @Nested
    class GetSuggestionsTests {

        @Test
        void existingPage_returnsCachedSuggestions() {
            PageSuggestion entity = PageSuggestion.builder()
                    .page("run")
                    .suggestions("[\"Frage 1\",\"Frage 2\"]")
                    .generatedAt(Instant.now())
                    .build();
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.of(entity));

            List<String> result = service.getSuggestions("run");

            assertThat(result).containsExactly("Frage 1", "Frage 2");
        }

        @Test
        void unknownPage_returnsDefaults() {
            when(suggestionRepository.findByPage("unknown")).thenReturn(Optional.empty());

            List<String> result = service.getSuggestions("unknown");

            assertThat(result).isNotEmpty();
            // Default for unknown pages
            assertThat(result).contains("Was lief heute?");
        }

        @Test
        void invalidJson_returnsDefaults() {
            PageSuggestion entity = PageSuggestion.builder()
                    .page("run")
                    .suggestions("not valid json")
                    .generatedAt(Instant.now())
                    .build();
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.of(entity));

            List<String> result = service.getSuggestions("run");

            // Falls back to empty list from deserialize, then handled by caller
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class DefaultsTests {

        @Test
        void runPage_returnsRunDefaults() {
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.empty());

            List<String> result = service.getSuggestions("run");

            assertThat(result).contains("Erkläre diesen Run", "Warum ist das fehlgeschlagen?");
        }

        @Test
        void workflowPage_returnsWorkflowDefaults() {
            when(suggestionRepository.findByPage("workflow")).thenReturn(Optional.empty());

            List<String> result = service.getSuggestions("workflow");

            assertThat(result).contains("Analysiere diesen Workflow");
        }

        @Test
        void todosPage_returnsTodoDefaults() {
            when(suggestionRepository.findByPage("todos")).thenReturn(Optional.empty());

            List<String> result = service.getSuggestions("todos");

            assertThat(result).contains("Was ist dringend?");
        }
    }

    @Nested
    class RefreshSuggestionsTests {

        @Test
        void aiReturnsValidJson_persistsSuggestions() {
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("run"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenReturn(new CliSession.EnrichedCommandResult(
                            "[\"Suggestion A\",\"Suggestion B\"]", 0, null));
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.empty());
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<String> result = service.refreshSuggestions("run");

            assertThat(result).containsExactly("Suggestion A", "Suggestion B");
            verify(suggestionRepository).save(any());
        }

        @Test
        void aiReturnsMoreThanMax_limitsTo4() {
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("run"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenReturn(new CliSession.EnrichedCommandResult(
                            "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]", 0, null));
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.empty());
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<String> result = service.refreshSuggestions("run");

            assertThat(result).hasSize(4);
        }

        @Test
        void aiReturnsInvalidJson_fallsBackToDefaults() {
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("run"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenReturn(new CliSession.EnrichedCommandResult(
                            "not a json array at all", 0, null));
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.empty());
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<String> result = service.refreshSuggestions("run");

            // Falls back to defaults for "run" page
            assertThat(result).contains("Erkläre diesen Run");
        }

        @Test
        void aiThrowsException_fallsBackToDefaults() {
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("run"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenThrow(new RuntimeException("AI unavailable"));
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.empty());
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<String> result = service.refreshSuggestions("run");

            assertThat(result).contains("Erkläre diesen Run");
        }
    }

    @Nested
    class PersistTests {

        @Test
        void existingPage_updatesEntity() {
            PageSuggestion existing = PageSuggestion.builder()
                    .id("ps-1")
                    .page("run")
                    .suggestions("[\"old\"]")
                    .generatedAt(Instant.now().minusSeconds(3600))
                    .build();
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("run"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenReturn(new CliSession.EnrichedCommandResult(
                            "[\"new\"]", 0, null));
            when(suggestionRepository.findByPage("run")).thenReturn(Optional.of(existing));
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshSuggestions("run");

            verify(suggestionRepository).save(argThat(s ->
                    s.getId().equals("ps-1") && s.getSuggestions().contains("new")));
        }

        @Test
        void newPage_createsEntity() {
            when(sessionRepository.findTop10ByPageOrderByStartedAtDesc("chains"))
                    .thenReturn(List.of());
            when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                    .thenReturn(List.of());
            when(commandExecutorService.executeOnHost(any()))
                    .thenReturn(new CliSession.EnrichedCommandResult(
                            "[\"Chain Frage\"]", 0, null));
            when(suggestionRepository.findByPage("chains")).thenReturn(Optional.empty());
            when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.refreshSuggestions("chains");

            verify(suggestionRepository).save(argThat(s ->
                    s.getPage().equals("chains")));
        }
    }

    @Nested
    class CancelRunningTests {

        @Test
        void noRunningThread_doesNothing() {
            // Should not throw
            service.cancelRunning();
        }
    }

    @Nested
    class InitTests {

        @Test
        void modelExists_cachesModel() {
            when(appSettingsRepository.get("suggestions-model"))
                    .thenReturn(Optional.of("claude-opus-4-6"));

            service.init();

            String cached = (String) ReflectionTestUtils.getField(service, "cachedSuggestionsModel");
            assertThat(cached).isEqualTo("claude-opus-4-6");
        }

        @Test
        void modelBlank_setsEmptyInDb() {
            when(appSettingsRepository.get("suggestions-model"))
                    .thenReturn(Optional.of("  "));

            service.init();

            verify(appSettingsRepository).set("suggestions-model", "");
        }

        @Test
        void modelAbsent_setsEmptyInDb() {
            when(appSettingsRepository.get("suggestions-model"))
                    .thenReturn(Optional.empty());

            service.init();

            verify(appSettingsRepository).set("suggestions-model", "");
        }
    }

    @Nested
    class RefreshCachedModelTests {

        @Test
        void refreshCachedModel_updatesCache() {
            when(appSettingsRepository.get("suggestions-model"))
                    .thenReturn(Optional.of("claude-sonnet-4-6"));

            service.refreshCachedModel();

            String cached = (String) ReflectionTestUtils.getField(service, "cachedSuggestionsModel");
            assertThat(cached).isEqualTo("claude-sonnet-4-6");
        }
    }
}
