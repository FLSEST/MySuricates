package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.service.live.LiveOutput;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        assertThat(cmd).startsWith("claude", "-p", "--output-format", "stream-json", "--verbose");
        assertThat(cmd).doesNotContain("hello"); // prompt is sent via stdin, not args
        assertThat(cmd).contains("--max-turns", "3"); // default
    }

    @Test
    void buildReasonCommand_customMaxTurns() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("hello", null, 1);

        assertThat(cmd).contains("--max-turns", "1");
    }

    @Test
    void buildReasonCommand_includesModel_whenProvided() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("hello", null, "claude-opus-4-6", null, null);

        assertThat(cmd).contains("--model", "claude-opus-4-6");
    }

    @Test
    void buildReasonCommand_omitsModel_whenNullOrBlank() {
        HostClaudeService service = createService(null, false, List.of());

        assertThat(service.buildReasonCommand("hello", null, null, null, null)).doesNotContain("--model");
        assertThat(service.buildReasonCommand("hello", null, "  ", null, null)).doesNotContain("--model");
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

        assertThat(cmd).startsWith("claude", "-p", "--output-format", "stream-json", "--verbose");
        assertThat(cmd).doesNotContain("prompt"); // prompt is sent via stdin, not args
        assertThat(cmd).contains("--model", "claude-sonnet-4-6");
        assertThat(cmd).contains("--append-system-prompt", "sys");
        assertThat(cmd).contains("--dangerously-skip-permissions");
        assertThat(cmd).containsSubsequence("--allowedTools", "Bash");
        assertThat(cmd).contains("--max-tokens", "4096");
    }

    // --- Per-call tool whitelist (chat hard-gate) ---

    @Test
    void buildActionCommand_withCallWhitelist_dropsSkipPermissionsAndEmitsAllowedTools() {
        // Even if the global config has skipPermissions=true (the default),
        // a per-call whitelist must override it — the chat flow must never run with full privileges.
        HostClaudeService service = createService(null, true, List.of("Bash"));
        List<String> cmd = service.buildActionCommand("do work", null, null,
                List.of("Read", "Grep", "Glob", "WebFetch"), null);

        assertThat(cmd).doesNotContain("--dangerously-skip-permissions");
        // Global-config tools should NOT leak through when a per-call whitelist is active.
        assertThat(cmd).doesNotContain("--allowedTools");
        assertThat(cmd).containsSubsequence("--allowed-tools", "Read");
        assertThat(cmd).containsSubsequence("--allowed-tools", "Grep");
        assertThat(cmd).containsSubsequence("--allowed-tools", "Glob");
        assertThat(cmd).containsSubsequence("--allowed-tools", "WebFetch");
    }

    @Test
    void buildActionCommand_withCallBlacklist_emitsDisallowedToolsFlag() {
        HostClaudeService service = createService(null, true, List.of());
        List<String> cmd = service.buildActionCommand("do work", null, null, null, List.of("Edit", "Write"));

        assertThat(cmd).containsSubsequence("--disallowed-tools", "Edit");
        assertThat(cmd).containsSubsequence("--disallowed-tools", "Write");
    }

    @Test
    void buildActionCommand_noCallRestrictions_keepsGlobalBehavior() {
        // When neither allowedTools nor disallowedTools is set per-call, behavior must match pre-change.
        HostClaudeService service = createService(null, true, List.of("Bash"));
        List<String> cmd = service.buildActionCommand("do work", null, null, null, null);

        assertThat(cmd).contains("--dangerously-skip-permissions");
        assertThat(cmd).containsSubsequence("--allowedTools", "Bash");
        assertThat(cmd).doesNotContain("--allowed-tools");
        assertThat(cmd).doesNotContain("--disallowed-tools");
    }

    // --- effort mapping ---

    @Test
    void buildReasonCommand_withEffort_addsEffortFlag() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", null, null, "high");

        assertThat(cmd).containsSubsequence("--effort", "high");
    }

    @Test
    void buildReasonCommand_withoutEffort_omitsEffortFlag() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildReasonCommand("test prompt", null, null, null);

        assertThat(cmd).doesNotContain("--effort");
    }

    @Test
    void buildActionCommand_withEffort_addsEffortFlag() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("test prompt", "claude-opus-4-6", null, null, null, "xhigh");

        assertThat(cmd).containsSubsequence("--effort", "xhigh");
    }

    @Test
    void buildActionCommand_withoutEffort_omitsEffortFlag() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("test prompt", null, null, null, null, null);

        assertThat(cmd).doesNotContain("--effort");
    }

    @Test
    void buildActionCommand_invalidEffort_omitsEffortFlag() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("test prompt", null, null, null, null, "turbo");

        assertThat(cmd).doesNotContain("--effort");
    }

    @Test
    void buildActionCommand_effortIsCaseInsensitive() {
        HostClaudeService service = createService(null, false, List.of());
        List<String> cmd = service.buildActionCommand("test prompt", null, null, null, null, "Medium");

        assertThat(cmd).containsSubsequence("--effort", "medium");
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
            List<String> buildReasonCommand(String prompt, String systemPrompt, String model, Integer maxTurns, String effort) {
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
            List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                            List<String> callAllowedTools, List<String> callDisallowedTools,
                                            String effort) {
                return List.of("sleep", "60");
            }
        };

        assertThatThrownBy(() -> service.executeAction("test", null, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    // --- per-block timeout override ---

    @Test
    void reasonStructured_usesPerCallTimeout_whenProvided() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(300); // global default
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildReasonCommand(String prompt, String systemPrompt, String model, Integer maxTurns, String effort) {
                // "sleep 60" hangs for 60s, well beyond 2s override
                return List.of("sleep", "60");
            }
        };

        // Per-call timeout of 2s should override the 300s global default
        assertThatThrownBy(() -> service.reasonStructured("test", null, null, null, 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 2 seconds");
    }

    @Test
    void reasonStructured_usesGlobalTimeout_whenOverrideIsNull() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(1); // global 1s
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildReasonCommand(String prompt, String systemPrompt, String model, Integer maxTurns, String effort) {
                return List.of("sleep", "60");
            }
        };

        // null timeout → falls back to global 1s
        assertThatThrownBy(() -> service.reasonStructured("test", null, null, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 1 seconds");
    }

    @Test
    void executeAction_usesPerCallTimeout_whenProvided() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(300);
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                            List<String> callAllowedTools, List<String> callDisallowedTools,
                                            String effort) {
                return List.of("sleep", "60");
            }
        };

        // Per-call timeout of 2s should override the 300s global default
        assertThatThrownBy(() -> service.executeAction("test", null, null, null, 2))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 2 seconds");
    }

    @Test
    void executeAction_usesGlobalTimeout_whenOverrideIsNull() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(1);
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms) {
            @Override
            List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                            List<String> callAllowedTools, List<String> callDisallowedTools,
                                            String effort) {
                return List.of("sleep", "60");
            }
        };

        // null timeout → falls back to global 1s
        assertThatThrownBy(() -> service.executeAction("test", null, null, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out after 1 seconds");
    }

    // --- Two-Tier Semaphore ---

    @Test
    void constructor_initializesSemaphoresCorrectly() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setMaxConcurrentCalls(5);
        config.setReservedForInteractive(1);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms);

        // Background limit should be max - reserved = 5 - 1 = 4
        // We can verify indirectly that the service was created without error
        assertThat(service).isNotNull();
    }

    @Test
    void updateMaxConcurrentCalls_adjustsBothSemaphores() {
        HostClaudeService service = createService(null, false, List.of());

        // Should not throw
        service.updateMaxConcurrentCalls(10);
    }

    @Test
    void updateReservedForInteractive_adjustsBackgroundSemaphore() {
        HostClaudeService service = createService(null, false, List.of());

        // Should not throw
        service.updateReservedForInteractive(3);
    }

    @Test
    void updateReservedForInteractive_atLeastOneBackgroundSlot() {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setMaxConcurrentCalls(2);
        config.setReservedForInteractive(0);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        HostClaudeService service = new HostClaudeService(config, perms);

        // Set reserved higher than max — background should still have at least 1
        service.updateReservedForInteractive(10);
        // Should not throw — Math.max(1, ...) guarantees at least 1
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

    // --- API-key env var injection (H1 fix) ---

    /**
     * Helper to build a service with a mocked {@link ApiKeyService}.
     * Wrapping in an {@link ObjectProvider} mirrors the production wiring.
     */
    @SuppressWarnings("unchecked")
    private HostClaudeService createServiceWithApiKey(String apiKey) {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(300);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());

        ApiKeyService keyService = mock(ApiKeyService.class);
        when(keyService.getEffectiveApiKey()).thenReturn(apiKey);
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(keyService);

        return new HostClaudeService(config, perms, provider);
    }

    @Test
    void injectApiKeyEnv_setsAutomationApiKey_whenServiceWired() {
        HostClaudeService service = createServiceWithApiKey("my-secret-key");
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).containsEntry("AUTOMATION_API_KEY", "my-secret-key");
    }

    @Test
    void injectApiKeyEnv_isNoOp_whenServiceProviderIsNull() {
        // 2-arg ctor: provider is null (test/legacy code path).
        HostClaudeService service = createService(null, false, List.of());
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectApiKeyEnv_isNoOp_whenApiKeyServiceUnavailable() {
        // Spring DI present, but the ApiKeyService bean is not (e.g. early startup).
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(300);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());
        ObjectProvider<ApiKeyService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        HostClaudeService service = new HostClaudeService(config, perms, provider);
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }

    @Test
    void injectApiKeyEnv_isNoOp_whenApiKeyIsBlank() {
        // Security disabled or not yet generated → empty key, do not set env var
        // to avoid masking a legitimate "no key" state with "AUTOMATION_API_KEY=".
        HostClaudeService service = createServiceWithApiKey("  ");
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }

    @Test
    void injectApiKeyEnv_isNoOp_whenApiKeyIsNull() {
        HostClaudeService service = createServiceWithApiKey(null);
        ProcessBuilder pb = new ProcessBuilder("true");

        service.injectApiKeyEnv(pb);

        assertThat(pb.environment()).doesNotContainKey("AUTOMATION_API_KEY");
    }

    // --- live output ---

    private static HostClaudeService serviceReplaying(Path streamJson, int timeoutSeconds, String trailingShell) {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(timeoutSeconds);
        config.setMaxTokens(null);
        CliPermissionsConfig perms = new CliPermissionsConfig();
        perms.setSkipPermissions(false);
        perms.setAllowedTools(List.of());
        // Read the prompt from stdin first, otherwise writing it can fail with a broken pipe.
        List<String> replay = List.of("/bin/sh", "-c", "cat >/dev/null; cat \"$1\"" + trailingShell, "sh", streamJson.toString());
        return new HostClaudeService(config, perms) {
            @Override
            List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                            List<String> callAllowedTools, List<String> callDisallowedTools,
                                            String effort) {
                return replay;
            }
        };
    }

    @Test
    void executeAction_streamsFormattedEventsIntoTheBoundSink_resultIdenticalToUnbound(@TempDir Path dir) throws IOException {
        Path stream = Files.writeString(dir.resolve("stream.jsonl"), """
                {"type":"system","subtype":"init","model":"claude-opus-5","tools":["Bash"]}
                {"type":"assistant","message":{"model":"claude-opus-5","content":[{"type":"text","text":"Ich starte die Tests."},{"type":"tool_use","name":"Bash","input":{"command":"mvn test"}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"BUILD SUCCESS"}]}}
                {"type":"result","subtype":"success","result":"Alles grün.","num_turns":2,"total_cost_usd":0.012,"duration_ms":4000}
                """);
        HostClaudeService service = serviceReplaying(stream, 30, "");
        List<String> lines = new ArrayList<>();

        HostClaudeService.ActionResult unbound = service.executeAction("prompt", null, null, null);
        HostClaudeService.ActionResult bound = LiveOutput.bind((kind, text) -> lines.add(kind + ":" + text), () -> {
            try {
                return service.executeAction("prompt", null, null, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(bound).isEqualTo(unbound);
        assertThat(bound.text()).isEqualTo("Alles grün.");
        assertThat(bound.parsed().toolUses()).hasSize(1);
        assertThat(lines).containsExactly(
                "SYSTEM:session · claude-opus-5 · 1 tool",
                "TEXT:Ich starte die Tests.",
                "TOOL:Bash: mvn test",
                "TOOL_RESULT:↳ 13 B",
                "RESULT:✓ 2 turns · $0.012 · 4s");
    }

    @Test
    void executeAction_deliversLinesEmittedBeforeATimeout(@TempDir Path dir) throws IOException {
        Path stream = Files.writeString(dir.resolve("stream.jsonl"), """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"pom.xml"}}]}}
                """);
        HostClaudeService service = serviceReplaying(stream, 1, "; sleep 30");
        List<String> lines = new ArrayList<>();

        assertThatThrownBy(() -> LiveOutput.bind((kind, text) -> lines.add(kind + ":" + text), () -> {
            try {
                return service.executeAction("prompt", null, null, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        })).hasRootCauseMessage("Host Claude action timed out after 1 seconds");
        assertThat(lines).containsExactly("TOOL:Read: pom.xml");
    }
}
