package com.automation.service.assistant;

import com.automation.config.HostClaudeConfig;
import com.automation.dto.AssistantChatRequest;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CLI side of the assistant chat on its own: seeding and caching the chat settings, and the one
 * request shape every chat call uses. The chat flow around it is covered in {@code AssistantServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AssistantChatCallerTest {

    @Mock private CommandExecutorService commandExecutorService;
    @Mock private AppSettingsRepository appSettingsRepository;

    private HostClaudeConfig hostClaudeConfig;
    private AssistantChatCaller caller;

    @BeforeEach
    void setUp() {
        hostClaudeConfig = new HostClaudeConfig();
        hostClaudeConfig.setAssistantChatTimeoutSeconds(240);
        caller = new AssistantChatCaller(commandExecutorService, hostClaudeConfig, appSettingsRepository);
    }

    private static AssistantChatRequest request(String provider) {
        return new AssistantChatRequest("Frage", List.of(), null, provider, null, null);
    }

    private void stubCli(String output, int exitCode) {
        when(commandExecutorService.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult(output, exitCode, null));
    }

    // ── seedSettings ─────────────────────────────────────────────────────────

    @Test
    void seedSettings_loadsBaseInstructionsFromClasspath() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.of("dummy"));

        caller.seedSettings();

        assertThat(caller.baseInstructions()).startsWith("# MySuricates Assistant");
        assertThat(caller.cachedModel()).isEqualTo("dummy");
    }

    @Test
    void seedSettings_seedsDefaultThinkingPhrases_whenMissing() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        caller.seedSettings();

        verify(appSettingsRepository).set(eq("assistant-thinking-phrases"),
                argThat(v -> v.startsWith("{\"de\"") && v.contains("\"en\"")));
    }

    @Test
    void seedSettings_migratesLegacyArrayThinkingPhrases() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.of("[\"Denke nach…\"]"));
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        caller.seedSettings();

        verify(appSettingsRepository).set(eq("assistant-thinking-phrases"), argThat(v -> v.startsWith("{")));
    }

    @Test
    void seedSettings_seedsDefaultModel_whenBlank() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.of("{\"de\":[]}"));
        when(appSettingsRepository.get("assistant-model"))
                .thenReturn(Optional.of(" "), Optional.of("sonnet"));

        caller.seedSettings();

        verify(appSettingsRepository).set("assistant-model", "sonnet");
        assertThat(caller.cachedModel()).isEqualTo("sonnet");
    }

    @Test
    void seedSettings_keepsPhrasesAndModelThatAreAlreadySet() {
        when(appSettingsRepository.get("assistant-thinking-phrases")).thenReturn(Optional.of("{\"de\":[\"Moment…\"]}"));
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("opus"));

        caller.seedSettings();

        verify(appSettingsRepository, never()).set(anyString(), anyString());
        assertThat(caller.cachedModel()).isEqualTo("opus");
    }

    // ── refreshCachedModel ───────────────────────────────────────────────────

    @Test
    void refreshCachedModel_readsTheCurrentSetting() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("opus"));

        caller.refreshCachedModel();

        assertThat(caller.cachedModel()).isEqualTo("opus");
    }

    @Test
    void refreshCachedModel_blankOrMissingSetting_leavesTheChoiceToTheProvider() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("  "), Optional.empty());

        caller.refreshCachedModel();
        assertThat(caller.cachedModel()).isNull();

        caller.refreshCachedModel();
        assertThat(caller.cachedModel()).isNull();
    }

    // ── call ─────────────────────────────────────────────────────────────────

    @Test
    void call_sendsAnInteractiveReadOnlyRequest() {
        stubCli("Antwort", 0);

        caller.call(request("CODEX"), "/work/dir", "Frage", "SYS-PROMPT", "opus");

        ArgumentCaptor<HostExecutionRequest> sent = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutorService).executeOnHost(sent.capture());
        HostExecutionRequest req = sent.getValue();
        assertThat(req.projectPath()).isEqualTo("/work/dir");
        assertThat(req.command()).isEqualTo("Frage");
        assertThat(req.systemPrompt()).isEqualTo("SYS-PROMPT");
        assertThat(req.providerId()).isEqualTo("CODEX");
        assertThat(req.model()).isEqualTo("opus");
        assertThat(req.timeoutSeconds()).isEqualTo(240);
        assertThat(req.priority()).isTrue();
        assertThat(req.allowedTools()).containsExactly("Read", "Grep", "Glob", "WebFetch");
    }

    @Test
    void call_zeroExit_returnsTheResultUnchanged() {
        stubCli("Antwort", 0);

        var result = caller.call(request(null), "/tmp", "Frage", "SYS", null);

        assertThat(result.output()).isEqualTo("Antwort");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void call_nonZeroExit_failsWithAShortenedSnippet() {
        stubCli("x".repeat(300), 2);

        assertThatThrownBy(() -> caller.call(request(null), "/tmp", "Frage", "SYS", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Assistant CLI failed (exit 2): " + "x".repeat(200) + "…");
    }

    @Test
    void call_nonZeroExitWithoutOutput_failsWithTheExitCode() {
        stubCli(null, 1);

        assertThatThrownBy(() -> caller.call(request(null), "/tmp", "Frage", "SYS", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Assistant CLI failed (exit 1): ");
    }
}
