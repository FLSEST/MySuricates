package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostClaudeServiceTest {

    private HostClaudeService createService(Integer maxTokens, boolean skipPermissions, List<String> allowedTools) {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(300);
        config.setMaxTokens(maxTokens);

        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(skipPermissions);
        perms.setAllowedTools(allowedTools);

        return new HostClaudeService(config, perms);
    }

    // --- buildReasonCommand ---

    @Test
    void buildReasonCommand_includesMaxTokens_whenConfigured() {
        HostClaudeService service = createService(16000, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", null);

        assertThat(cmd).contains("--max-tokens", "16000");
    }

    @Test
    void buildReasonCommand_omitsMaxTokens_whenNull() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", null);

        assertThat(cmd).doesNotContain("--max-tokens");
    }

    @Test
    void buildReasonCommand_includesSystemPrompt_whenProvided() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", "system instructions");

        assertThat(cmd).contains("--system-prompt", "system instructions");
    }

    @Test
    void buildReasonCommand_omitsSystemPrompt_whenBlank() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", "  ");

        assertThat(cmd).doesNotContain("--system-prompt");
    }

    @Test
    void buildReasonCommand_baseStructure() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("hello", null);

        assertThat(cmd).startsWith("claude", "-p", "hello", "--output-format", "stream-json", "--verbose");
        assertThat(cmd).contains("--max-turns", "3"); // default
    }

    @Test
    void buildReasonCommand_customMaxTurns() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("hello", null, 1);

        assertThat(cmd).contains("--max-turns", "1");
    }

    // --- buildActionCommand ---

    @Test
    void buildActionCommand_includesMaxTokens_whenConfigured() {
        HostClaudeService service = createService(8000, true, List.of());
        List<String> cmd = service.buildActionCommand("do work", "claude-sonnet-4-6", null);

        assertThat(cmd).contains("--max-tokens", "8000");
    }

    @Test
    void buildActionCommand_omitsMaxTokens_whenNull() {
        HostClaudeService service = createService(null, true, List.of());
        List<String> cmd = service.buildActionCommand("do work", null, null);

        assertThat(cmd).doesNotContain("--max-tokens");
    }

    @Test
    void buildActionCommand_includesModel_whenProvided() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("do work", "claude-opus-4-6", null);

        assertThat(cmd).contains("--model", "claude-opus-4-6");
    }

    @Test
    void buildActionCommand_omitsModel_whenBlank() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("do work", "", null);

        assertThat(cmd).doesNotContain("--model");
    }

    @Test
    void buildActionCommand_includesAppendSystemPrompt_whenProvided() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("do work", null, "instructions");

        assertThat(cmd).contains("--append-system-prompt", "instructions");
    }

    @Test
    void buildActionCommand_includesSkipPermissions_whenEnabled() {
        HostClaudeService service = createService(null, true, List.of());
        List<String> cmd = service.buildActionCommand("do work", null, null);

        assertThat(cmd).contains("--dangerously-skip-permissions");
    }

    @Test
    void buildActionCommand_omitsSkipPermissions_whenDisabled() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("do work", null, null);

        assertThat(cmd).doesNotContain("--dangerously-skip-permissions");
    }

    @Test
    void buildActionCommand_includesAllowedTools() {
        HostClaudeService service = createService(null, false, List.of("Bash", "Read", "Write"));
        List<String> cmd = service.buildActionCommand("do work", null, null);

        // Each tool gets its own --allowedTools flag
        assertThat(cmd).containsSubsequence("--allowedTools", "Bash");
        assertThat(cmd).containsSubsequence("--allowedTools", "Read");
        assertThat(cmd).containsSubsequence("--allowedTools", "Write");
    }

    @Test
    void buildActionCommand_allFlags_combined() {
        HostClaudeService service = createService(4096, true, List.of("Bash"));
        List<String> cmd = service.buildActionCommand("prompt", "claude-sonnet-4-6", "sys");

        assertThat(cmd).startsWith("claude", "-p", "prompt", "--output-format", "stream-json", "--verbose");
        assertThat(cmd).contains("--model", "claude-sonnet-4-6");
        assertThat(cmd).contains("--append-system-prompt", "sys");
        assertThat(cmd).contains("--dangerously-skip-permissions");
        assertThat(cmd).containsSubsequence("--allowedTools", "Bash");
        assertThat(cmd).contains("--max-tokens", "4096");
    }

    // --- timeout behavior ---

    @Test
    void reasonStructured_timesOut_whenProcessHangs() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(1); // 1 second timeout
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        // Use a service that will execute a hanging process
        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildReasonCommand(String prompt, String systemPrompt) {
                // "sleep 60" will hang for 60s, well beyond 1s timeout
                return List.of("sleep", "60");
            }
        };

        assertThatThrownBy(() -> service.reasonStructured("test"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void executeAction_timesOut_whenProcessHangs() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(1);
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildActionCommand(String prompt, String model, String systemPrompt) {
                return List.of("sleep", "60");
            }
        };

        assertThatThrownBy(() -> service.executeAction("test", null, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    // --- buildConversationPrompt ---

    @Test
    void buildConversationPrompt_emptyHistory() {
        HostClaudeService service = createService(null, false, List.of());
        String result = service.buildConversationPrompt("system", List.of());

        assertThat(result).contains("=== YOUR RESPONSE ===");
        assertThat(result).doesNotContain("CONVERSATION HISTORY");
    }

    @Test
    void buildConversationPrompt_withHistory() {
        HostClaudeService service = createService(null, false, List.of());
        var history = List.of(
                new HostClaudeService.Turn("user", "Hello"),
                new HostClaudeService.Turn("assistant", "Hi there")
        );
        String result = service.buildConversationPrompt("system", history);

        assertThat(result).contains("=== CONVERSATION HISTORY ===");
        assertThat(result).contains("[USER]");
        assertThat(result).contains("Hello");
        assertThat(result).contains("[ASSISTANT]");
        assertThat(result).contains("Hi there");
    }
}
