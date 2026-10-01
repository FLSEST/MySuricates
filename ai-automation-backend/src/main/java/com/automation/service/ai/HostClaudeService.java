package com.automation.service.ai;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.service.cli.claude.ClaudeCliOutcome;
import com.automation.service.cli.claude.ClaudeJsonResponse;
import com.automation.service.cli.claude.ClaudeOutputParser;
import com.automation.service.cli.claude.ClaudeStreamEventFormatter;
import com.automation.service.live.LiveOutput;
import com.automation.util.ChildProcessEnv;
import com.automation.util.ProcessDrain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class HostClaudeService {

    public record Turn(String role, String content) {}

    public record ReasoningResult(String text, ClaudeJsonResponse parsed) {}

    public record ActionResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private final HostClaudeConfig config;
    private final CliPermissionsConfig permissionsConfig;
    private final DynamicSemaphore callSemaphore;
    private final DynamicSemaphore backgroundSemaphore;
    private final AtomicLong lastCallTimestamp = new AtomicLong(0);

    public HostClaudeService(HostClaudeConfig config, CliPermissionsConfig permissionsConfig) {
        this.config = config;
        this.permissionsConfig = permissionsConfig;
        int total = config.getMaxConcurrentCalls();
        int reserved = config.getReservedForInteractive();
        this.callSemaphore = new DynamicSemaphore(total);
        this.backgroundSemaphore = new DynamicSemaphore(Math.max(1, total - reserved));
        log.info("Claude CLI throttle: maxConcurrent={}, reservedForInteractive={}, backgroundLimit={}, interCallDelay={}ms",
                total, reserved, total - reserved, config.getInterCallDelayMs());
    }

    public void updateMaxConcurrentCalls(int newMax) {
        callSemaphore.setMaxPermits(newMax);
        int backgroundMax = Math.max(1, newMax - config.getReservedForInteractive());
        backgroundSemaphore.setMaxPermits(backgroundMax);
        log.info("Claude CLI max concurrent calls updated to {} (background limit: {})", newMax, backgroundMax);
    }

    public void updateReservedForInteractive(int reserved) {
        int backgroundMax = Math.max(1, callSemaphore.getMaxPermits() - reserved);
        backgroundSemaphore.setMaxPermits(backgroundMax);
        log.info("Claude CLI reserved for interactive updated to {} (background limit: {})", reserved, backgroundMax);
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
        return reasonStructured(prompt, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt) throws IOException {
        return reasonStructured(prompt, systemPrompt, null, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt,
                                             String workingDirectory, Integer maxTurns) throws IOException {
        return reasonStructured(prompt, systemPrompt, workingDirectory, maxTurns, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt,
                                             String workingDirectory, Integer maxTurns,
                                             Integer timeoutSeconds) throws IOException {
        return reasonStructured(prompt, systemPrompt, workingDirectory, maxTurns, timeoutSeconds, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt,
                                             String workingDirectory, Integer maxTurns,
                                             Integer timeoutSeconds, String effort) throws IOException {
        return reasonStructured(prompt, systemPrompt, null, workingDirectory, maxTurns, timeoutSeconds, effort);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt, String model,
                                             String workingDirectory, Integer maxTurns,
                                             Integer timeoutSeconds, String effort) throws IOException {
        List<String> cmd = buildReasonCommand(prompt, systemPrompt, model, maxTurns, effort);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host Claude reasoning");
        return new ReasoningResult(CliExit.answerOrThrow("Host Claude reasoning", result.exitCode, result.text,
                result.parsed.failed()), result.parsed);
    }

    /**
     * Executes a Claude CLI action on the host with the project as working directory.
     * Uses --append-system-prompt for block instructions, --model for model selection,
     * and --dangerously-skip-permissions with --allowedTools from CliPermissionsConfig.
     * Removes CLAUDE_CODE env var to prevent nested-session detection.
     */
    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, null, false);
    }

    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory,
                                       Integer timeoutSeconds) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, false);
    }

    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory,
                                       Integer timeoutSeconds, boolean priority) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, null, null);
    }

    /**
     * Action call with explicit tool restrictions. When {@code allowedTools} is non-empty,
     * {@code --dangerously-skip-permissions} is suppressed and only the listed tools are available
     * (via {@code --allowed-tools}). Used by the assistant chat flow to prevent ungated
     * Edit/Write/Bash execution in the user's project directory.
     */
    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory,
                                       Integer timeoutSeconds, boolean priority,
                                       List<String> allowedTools, List<String> disallowedTools) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority,
                allowedTools, disallowedTools, null);
    }

    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory,
                                       Integer timeoutSeconds, boolean priority,
                                       List<String> allowedTools, List<String> disallowedTools,
                                       String effort) throws IOException {
        List<String> cmd = buildActionCommand(prompt, model, systemPrompt, allowedTools, disallowedTools, effort);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host Claude action", priority);
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
        acquireThrottle(priority);
        try {
            // The agent may run prompts with foreign content unattended — it gets no
            // instance API key (TODO 62037bf2).
            ProcessBuilder pb = ChildProcessEnv.forAgent(cmd, workingDirectory);
            pb.environment().remove("CLAUDE_CODE");
            pb.environment().remove("CLAUDECODE");

            log.debug("{} (prompt length: {} chars, workDir: {})", label, prompt.length(), workingDirectory);
            Process proc = pb.start();
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
        } finally {
            callSemaphore.release();
            if (!priority) {
                backgroundSemaphore.release();
            }
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
     * Builds a conversation prompt from turn history (without system prompt).
     * The system prompt should be passed via --system-prompt flag for proper separation.
     */
    public String buildConversationPrompt(String systemPrompt, List<Turn> history) {
        StringBuilder sb = new StringBuilder();

        if (!history.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (Turn turn : history) {
                String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
                sb.append("[").append(label).append("]\n");
                sb.append(turn.content());
                sb.append("\n\n");
            }
        }

        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the system instructions.");

        return sb.toString();
    }

    /**
     * Acquires throttle permits: priority calls only need the global semaphore,
     * background calls also need the background semaphore (reserving capacity for interactive use).
     */
    private void acquireThrottle(boolean priority) throws IOException {
        try {
            if (!priority) {
                backgroundSemaphore.acquire();
            }
            callSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!priority) {
                backgroundSemaphore.release();
            }
            throw new IOException("Interrupted while waiting for Claude CLI throttle permit", e);
        }
        long delayMs = config.getInterCallDelayMs();
        if (delayMs > 0) {
            long remaining = delayMs - (System.currentTimeMillis() - lastCallTimestamp.get());
            if (remaining > 0) {
                log.debug("Claude CLI throttle: waiting {}ms before next call", remaining);
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    callSemaphore.release();
                    if (!priority) {
                        backgroundSemaphore.release();
                    }
                    throw new IOException("Interrupted during Claude CLI throttle delay", e);
                }
            }
        }
        lastCallTimestamp.set(System.currentTimeMillis());
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
