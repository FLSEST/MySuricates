package com.automation.service.ai;

import com.automation.config.HostPiConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPiServiceTest {

    private HostPiService createService(String defaultBackend, String defaultThinking) {
        HostPiConfig config = new HostPiConfig();
        config.setTimeoutSeconds(300);
        config.setDefaultBackendProvider(defaultBackend);
        config.setDefaultThinkingLevel(defaultThinking);
        return new HostPiService(config);
    }

    private HostPiService createDefaultService() {
        return createService("anthropic", "medium");
    }

    // --- baseCommand ---

    @Test
    void buildActionCommand_baseStructure() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, null);

        assertThat(cmd).startsWith("pi", "-p", "--no-session", "--no-context-files");
    }

    @Test
    void buildReasonCommand_baseStructure_addsNoToolsAndThinking() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, null);

        assertThat(cmd).startsWith("pi", "-p", "--no-session", "--no-context-files");
        assertThat(cmd).contains("--no-tools");
        assertThat(cmd).containsSubsequence("--thinking", "medium");
    }

    // --- model parsing & backend routing ---

    @Test
    void buildActionCommand_modelWithBackendPrefix_splitsIntoProviderAndModel() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand("google/gemini-2.0-flash", null, null, null);

        assertThat(cmd).containsSubsequence("--provider", "google");
        assertThat(cmd).containsSubsequence("--model", "gemini-2.0-flash");
    }

    @Test
    void buildActionCommand_modelWithMultipleSlashes_keepsRestAsModelId() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(
                "openrouter/meta-llama/llama-3.3-70b-instruct", null, null, null);

        assertThat(cmd).containsSubsequence("--provider", "openrouter");
        assertThat(cmd).containsSubsequence("--model", "meta-llama/llama-3.3-70b-instruct");
    }

    @Test
    void buildActionCommand_modelWithoutSlash_usesDefaultBackend() {
        HostPiService service = createService("anthropic", "medium");
        List<String> cmd = service.buildActionCommand("claude-sonnet-4-6", null, null, null);

        assertThat(cmd).containsSubsequence("--provider", "anthropic");
        assertThat(cmd).containsSubsequence("--model", "claude-sonnet-4-6");
    }

    @Test
    void buildActionCommand_blankModel_omitsBothFlags() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand("", null, null, null);

        assertThat(cmd).doesNotContain("--provider");
        assertThat(cmd).doesNotContain("--model");
    }

    @Test
    void buildActionCommand_nullModel_omitsBothFlags() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, null);

        assertThat(cmd).doesNotContain("--provider");
        assertThat(cmd).doesNotContain("--model");
    }

    @Test
    void parseModel_emptyBackendOrModel_fallsBackToDefault() {
        HostPiService service = createService("openai", "medium");
        // leading slash → empty backend
        HostPiService.BackendModel parsed = service.parseModel("/gpt-4o");
        assertThat(parsed.backend()).isEqualTo("openai");
        assertThat(parsed.modelId()).isEqualTo("/gpt-4o");
    }

    // --- system prompt ---

    @Test
    void buildActionCommand_systemPrompt_appendsAsAppendFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, "block instructions", null, null);

        assertThat(cmd).containsSubsequence("--append-system-prompt", "block instructions");
    }

    @Test
    void buildActionCommand_blankSystemPrompt_omitsFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, "  ", null, null);

        assertThat(cmd).doesNotContain("--append-system-prompt");
    }

    @Test
    void buildReasonCommand_systemPrompt_replacesViaSystemPromptFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand("system instructions", null, null);

        assertThat(cmd).containsSubsequence("--system-prompt", "system instructions");
    }

    // --- tool restrictions ---

    @Test
    void buildActionCommand_allowedTools_passedAsCommaSeparatedToolsFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, List.of("read", "grep", "find"), null);

        assertThat(cmd).containsSubsequence("--tools", "read,grep,find");
    }

    @Test
    void buildActionCommand_emptyAllowedTools_omitsToolsFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, List.of(), null);

        assertThat(cmd).doesNotContain("--tools");
    }

    @Test
    void buildActionCommand_disallowedTools_logsButDoesNotAddFlag() {
        // pi has no --disallowed-tools; we accept the param for interface compat but skip it.
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, List.of("Edit", "Write"));

        assertThat(cmd).doesNotContain("--disallowed-tools");
    }

    // --- thinking level mapping ---

    @Test
    void buildReasonCommand_maxTurnsOne_mapsToLow() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, 1);

        assertThat(cmd).containsSubsequence("--thinking", "low");
    }

    @Test
    void buildReasonCommand_maxTurnsThree_mapsToMedium() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, 3);

        assertThat(cmd).containsSubsequence("--thinking", "medium");
    }

    @Test
    void buildReasonCommand_maxTurnsFiveOrMore_mapsToHigh() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, 5);

        assertThat(cmd).containsSubsequence("--thinking", "high");
    }

    @Test
    void buildReasonCommand_nullMaxTurns_usesConfiguredDefault() {
        HostPiService service = createService("anthropic", "xhigh");
        List<String> cmd = service.buildReasonCommand(null, null, null);

        assertThat(cmd).containsSubsequence("--thinking", "xhigh");
    }

    // --- combined ---

    @Test
    void buildActionCommand_allFlags_combined() {
        HostPiService service = createService("anthropic", "medium");
        List<String> cmd = service.buildActionCommand(
                "openrouter/anthropic/claude-sonnet-4-6",
                "instructions",
                List.of("read", "bash"),
                null);

        assertThat(cmd).startsWith("pi", "-p", "--no-session", "--no-context-files");
        assertThat(cmd).containsSubsequence("--provider", "openrouter");
        assertThat(cmd).containsSubsequence("--model", "anthropic/claude-sonnet-4-6");
        assertThat(cmd).containsSubsequence("--append-system-prompt", "instructions");
        assertThat(cmd).containsSubsequence("--tools", "read,bash");
    }

    // --- effort mapping ---

    @Test
    void buildActionCommand_withEffort_addsThinkingFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, null, "high");

        assertThat(cmd).containsSubsequence("--thinking", "high");
    }

    @Test
    void buildActionCommand_withoutEffort_omitsThinkingFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, null, null);

        assertThat(cmd).doesNotContain("--thinking");
    }

    @Test
    void buildActionCommand_invalidEffort_omitsThinkingFlag() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildActionCommand(null, null, null, null, "turbo");

        assertThat(cmd).doesNotContain("--thinking");
    }

    @Test
    void buildReasonCommand_effortOverridesMaxTurnsHeuristic() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, 1, "xhigh");

        assertThat(cmd).containsSubsequence("--thinking", "xhigh");
        assertThat(cmd).doesNotContain("low");
    }

    @Test
    void buildReasonCommand_withoutEffort_keepsMaxTurnsHeuristic() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, 1, null);

        assertThat(cmd).containsSubsequence("--thinking", "low");
    }

    @Test
    void buildReasonCommand_effortIsCaseInsensitive() {
        HostPiService service = createDefaultService();
        List<String> cmd = service.buildReasonCommand(null, null, null, "HIGH");

        assertThat(cmd).containsSubsequence("--thinking", "high");
    }

    // --- timeout behavior ---

    @Test
    void executeAction_timesOut_whenProcessHangs() {
        HostPiConfig config = new HostPiConfig();
        config.setTimeoutSeconds(1);
        config.setDefaultBackendProvider("anthropic");
        config.setDefaultThinkingLevel("medium");

        HostPiService service = new HostPiService(config) {
            @Override
            List<String> buildActionCommand(String model, String systemPrompt,
                                             List<String> allowedTools, List<String> disallowedTools,
                                             String effort) {
                return List.of("sleep", "60");
            }
        };

        assertThatThrownBy(() -> service.executeAction("test", null, null, null, null, false, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void reason_usesPerCallTimeout_whenProvided() {
        HostPiConfig config = new HostPiConfig();
        config.setTimeoutSeconds(300);
        config.setDefaultBackendProvider("anthropic");
        config.setDefaultThinkingLevel("medium");

        HostPiService service = new HostPiService(config) {
            @Override
            List<String> buildReasonCommand(String systemPrompt, String model, Integer maxTurns, String effort) {
                return List.of("sleep", "60");
            }
        };

        assertThatThrownBy(() -> service.reason("test", null, null, null, null, 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 2 seconds");
    }
}
