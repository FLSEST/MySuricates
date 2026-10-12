package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.claude.ClaudeCliOutcome;
import com.automation.service.cli.claude.ClaudeJsonResponse;
import com.automation.service.cli.claude.ClaudeOutputParser;
import com.automation.service.cli.claude.ClaudeStreamEventFormatter;
import com.automation.service.live.LiveOutput;
import com.automation.util.ChildProcessEnv;
import com.automation.util.ProcessDrain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class HostClaudeService {

    public record ReasoningResult(String text, ClaudeJsonResponse parsed) {}

    public record ActionResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private final HostClaudeConfig config;
    private final CliPermissionsConfig permissionsConfig;
    private final AiCliThrottles throttles;
    private final ModuleRegistry modules; // null when built by hand in tests: module mcp counts as off

    public HostClaudeService(HostClaudeConfig config, CliPermissionsConfig permissionsConfig, AiCliThrottles throttles) {
        this(config, permissionsConfig, throttles, null);
    }

    @Autowired
    public HostClaudeService(HostClaudeConfig config, CliPermissionsConfig permissionsConfig, AiCliThrottles throttles,
                             ModuleRegistry modules) {
        this.config = config;
        this.permissionsConfig = permissionsConfig;
        this.throttles = throttles;
        this.modules = modules;
    }

    /**
     * Sends a single reasoning prompt to the local Claude CLI on the host.
     * Uses one-off mode with stream-json for structured metadata capture.
     */
    public String reason(String prompt) throws IOException {
        return reasonStructured(prompt).text();
    }

    /**
     * Sends a reasoning prompt and returns both text and structured metadata (tokens, cost, model,
     * tool usage). An exit code ≠ 0 throws: the CLI then printed an error, not an answer ({@link CliExit}).
     */
    public ReasoningResult reasonStructured(String prompt) throws IOException {
        return reasonStructured(new AiProvider.ReasoningRequest(prompt));
    }

    /**
     * Like {@link #reasonStructured(String)} with every setting of the request. One request record instead of
     * positional overloads: prompt, system prompt, model and working directory are all strings and were easy to
     * swap without the compiler noticing.
     */
    public ReasoningResult reasonStructured(AiProvider.ReasoningRequest request) throws IOException {
        List<String> cmd = ClaudeToolFlags.isolateFromUserMcpServers(
                buildReasonCommand(request.prompt(), request.systemPrompt(), request.model(), request.maxTurns(),
                        request.effort()), modules, false);
        var result = executeProcess(cmd, request.prompt(), request.workingDirectory(), request.timeoutSeconds(),
                "Host Claude reasoning");
        return new ReasoningResult(CliExit.answerOrThrow("Host Claude reasoning", result.exitCode, result.text,
                result.parsed.failed()), result.parsed);
    }

    /**
     * Executes a Claude CLI action on the host with the project as working directory.
     * Uses --append-system-prompt for block instructions, --model for model selection,
     * and --dangerously-skip-permissions with --allowedTools from CliPermissionsConfig.
     * Removes CLAUDE_CODE env var to prevent nested-session detection.
     */
    public ActionResult executeAction(AiProvider.ActionRequest request) throws IOException {
        // With allowedTools set, --dangerously-skip-permissions is suppressed and only those tools are available
        // (assistant chat: no ungated Edit/Write/Bash in the user's project). userMcpServers: the block opted in
        // to the user's MCP servers ({@link ClaudeToolFlags#isolateFromUserMcpServers}).
        List<String> cmd = ClaudeToolFlags.isolateFromUserMcpServers(
                buildActionCommand(request.prompt(), request.model(), request.systemPrompt(), request.allowedTools(),
                        request.disallowedTools(), request.effort()), modules, request.userMcpServers());
        var result = executeProcess(cmd, request.prompt(), request.workingDirectory(), request.timeoutSeconds(),
                "Host Claude action", request.priority());
        return new ActionResult(result.text, result.exitCode, result.parsed);
    }

    /**
     * Shared process execution logic — eliminates duplication between reasonStructured and executeAction.
     */
    private record ProcessResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private ProcessResult executeProcess(List<String> cmd, String prompt, String workingDirectory,
                                          Integer timeoutSeconds, String label) throws IOException {
        return executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, label, false);
    }

    private ProcessResult executeProcess(List<String> cmd, String prompt, String workingDirectory,
                                          Integer timeoutSeconds, String label, boolean priority) throws IOException {
        try (var permit = throttles.acquire(AiCliThrottles.CLAUDE, priority)) {
            // The agent may run prompts with foreign content unattended — it gets no
            // instance API key (TODO 62037bf2).
            ProcessBuilder pb = ChildProcessEnv.forAgent(cmd, workingDirectory);
            pb.environment().remove("CLAUDE_CODE");
            pb.environment().remove("CLAUDECODE");

            log.debug("{} (prompt length: {} chars, workDir: {})", label, prompt.length(), workingDirectory);
            Process proc = ChildProcessEnv.start(pb);
            writePromptToStdin(proc, prompt);

            int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : config.getTimeoutSeconds();
            String rawOutput = ProcessDrain.drain(proc, effectiveTimeout, label, ClaudeStreamEventFormatter.listener(LiveOutput.current()));

            int exitCode = proc.exitValue();
            ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(rawOutput);
            var outcome = ClaudeCliOutcome.of(rawOutput, exitCode, parsed);
            if (outcome.exitCode() != 0) {
                log.warn("{} returned exit code {}: {}", label, exitCode, truncate(outcome.text(), 500));
            }
            return new ProcessResult(outcome.text(), outcome.exitCode(), parsed);
        }
    }

    /**
     * Builds the CLI command list for a reasoning call.
     * Package-private for testability.
     */
    List<String> buildReasonCommand(String prompt, String systemPrompt) {
        return buildReasonCommand(prompt, systemPrompt, null);
    }

    List<String> buildReasonCommand(String prompt, String systemPrompt, Integer maxTurns) {
        return buildReasonCommand(prompt, systemPrompt, maxTurns, null);
    }

    List<String> buildReasonCommand(String prompt, String systemPrompt, Integer maxTurns, String effort) {
        return buildReasonCommand(prompt, systemPrompt, null, maxTurns, effort);
    }

    List<String> buildReasonCommand(String prompt, String systemPrompt, String model, Integer maxTurns, String effort) {
        List<String> cmd = new ArrayList<>(List.of(
                "claude", "-p",
                "--output-format", "stream-json", "--verbose"
        ));

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        // Skip permissions to prevent hanging on interactive tool approval prompts
        if (permissionsConfig.isSkipPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }

        cmd.add("--max-turns");
        cmd.add(String.valueOf(maxTurns != null ? maxTurns : 3));

        ReasoningEffort.parse(effort).ifPresent(e -> {
            cmd.add("--effort");
            cmd.add(e.cliValue());
        });

        if (config.getMaxTokens() != null) {
            cmd.add("--max-tokens");
            cmd.add(config.getMaxTokens().toString());
        }

        return cmd;
    }

    /**
     * Builds the CLI command list for an action call.
     * Package-private for testability.
     */
    List<String> buildActionCommand(String prompt, String model, String systemPrompt) {
        return buildActionCommand(prompt, model, systemPrompt, null, null);
    }

    List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                    List<String> callAllowedTools, List<String> callDisallowedTools) {
        return buildActionCommand(prompt, model, systemPrompt, callAllowedTools, callDisallowedTools, null);
    }

    List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                    List<String> callAllowedTools, List<String> callDisallowedTools,
                                    String effort) {
        List<String> cmd = new ArrayList<>(List.of(
                "claude", "-p",
                "--output-format", "stream-json", "--verbose"
        ));

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        ReasoningEffort.parse(effort).ifPresent(e -> {
            cmd.add("--effort");
            cmd.add(e.cliValue());
        });
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }

        ClaudeToolFlags.apply(cmd, callAllowedTools, callDisallowedTools, permissionsConfig);

        if (config.getMaxTokens() != null) {
            cmd.add("--max-tokens");
            cmd.add(config.getMaxTokens().toString());
        }

        return cmd;
    }

    /**
     * Writes the prompt to the process's stdin and closes it.
     * This avoids OS command-line argument length limits (E2BIG / errno 7).
     */
    private void writePromptToStdin(Process proc, String prompt) throws IOException {
        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
