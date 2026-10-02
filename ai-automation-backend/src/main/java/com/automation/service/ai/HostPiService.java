package com.automation.service.ai;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.HostPiConfig;
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

/**
 * Runs the `pi` coding agent (npm package `@earendil-works/pi-coding-agent`, formerly
 * `@mariozechner/pi-coding-agent`) on the host.
 * Analogous to {@link HostClaudeService} but for the `pi` CLI.
 *
 * <p>Pi is itself a multi-provider hub: a single `pi -p --provider X --model Y` call can
 * route to Anthropic, OpenAI, Google Gemini, xAI Grok, Groq, Cerebras, OpenRouter, or any
 * custom provider configured in {@code ~/.pi/agent/models.json} (e.g. Ollama / LM Studio / vLLM).
 *
 * <p>The MySuricates {@code workflow.model} string carries backend routing via the
 * {@code "<backend>/<model-id>"} convention (e.g. {@code "google/gemini-2.0-flash"},
 * {@code "openrouter/meta-llama/llama-3.3-70b-instruct"}). When no slash is present,
 * {@link HostPiConfig#getDefaultBackendProvider()} is used as the backend.
 */
@Service
@Slf4j
public class HostPiService {

    public record ActionResult(String text, int exitCode) {}

    /** @param thinkingLevel the {@code --thinking} level the call ran with (TODO 20861200) */
    public record ReasoningResult(String text, String thinkingLevel) {
        public ReasoningResult(String text) {
            this(text, null);
        }
    }

    /** Parsed model string: backend provider name + model id (may itself contain slashes). */
    public record BackendModel(String backend, String modelId) {}

    private final HostPiConfig config;
    private final DynamicSemaphore callSemaphore;
    private final DynamicSemaphore backgroundSemaphore;
    private final AtomicLong lastCallTimestamp = new AtomicLong(0);

    public HostPiService(HostPiConfig config) {
        this.config = config;
        int total = config.getMaxConcurrentCalls();
        int reserved = config.getReservedForInteractive();
        this.callSemaphore = new DynamicSemaphore(total);
        this.backgroundSemaphore = new DynamicSemaphore(Math.max(1, total - reserved));
        log.info("Pi CLI throttle: maxConcurrent={}, reservedForInteractive={}, backgroundLimit={}, defaultBackend={}",
                total, reserved, total - reserved, config.getDefaultBackendProvider());
    }

    public void updateMaxConcurrentCalls(int newMax) {
        callSemaphore.setMaxPermits(newMax);
        int backgroundMax = Math.max(1, newMax - config.getReservedForInteractive());
        backgroundSemaphore.setMaxPermits(backgroundMax);
        log.info("Pi CLI max concurrent calls updated to {} (background limit: {})", newMax, backgroundMax);
    }

    public void updateReservedForInteractive(int reserved) {
        int backgroundMax = Math.max(1, callSemaphore.getMaxPermits() - reserved);
        backgroundSemaphore.setMaxPermits(backgroundMax);
        log.info("Pi CLI reserved for interactive updated to {} (background limit: {})", reserved, backgroundMax);
    }

    /**
     * Reasoning call: read-only analysis, no tool execution ({@code --no-tools}); an exit code ≠ 0 throws
     * ({@link CliExit}). An explicit {@code effort} wins; otherwise the {@code maxTurns} hint is mapped
     * onto pi's {@code --thinking} level: 1 → low, 3 → medium, ≥5 → high.
     */
    public ReasoningResult reason(String prompt, String systemPrompt, String model,
                                   String workingDirectory, Integer maxTurns,
                                   Integer timeoutSeconds, String effort) throws IOException {
        List<String> cmd = buildReasonCommand(systemPrompt, model, maxTurns, effort);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host pi reasoning", false);
        return new ReasoningResult(CliExit.answerOrThrow("Host pi reasoning", result.exitCode, result.text),
                reasonThinkingLevel(maxTurns, effort));
    }

    /** Backwards-compat overload for call-sites written before effort support existed. */
    public ReasoningResult reason(String prompt, String systemPrompt, String model,
                                   String workingDirectory, Integer maxTurns,
                                   Integer timeoutSeconds) throws IOException {
        return reason(prompt, systemPrompt, model, workingDirectory, maxTurns, timeoutSeconds, null);
    }

    public ActionResult executeAction(String prompt, String model, String systemPrompt,
                                       String workingDirectory, Integer timeoutSeconds,
                                       boolean priority,
                                       List<String> allowedTools,
                                       List<String> disallowedTools, String effort) throws IOException {
        List<String> cmd = buildActionCommand(model, systemPrompt, allowedTools, disallowedTools, effort);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host pi action", priority);
        return new ActionResult(result.text, result.exitCode);
    }

    /** Backwards-compat overload for call-sites written before effort support existed. */
    public ActionResult executeAction(String prompt, String model, String systemPrompt,
                                       String workingDirectory, Integer timeoutSeconds,
                                       boolean priority,
                                       List<String> allowedTools,
                                       List<String> disallowedTools) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, timeoutSeconds,
                priority, allowedTools, disallowedTools, null);
    }

    // --- Command builders (package-private for testability) ---

    /**
     * Builds the pi CLI command for a reasoning call.
     * Reasoning is read-only — disable all tools so the model only thinks/responds.
     * An explicit effort level overrides the maxTurns-derived thinking heuristic.
     */
    List<String> buildReasonCommand(String systemPrompt, String model, Integer maxTurns, String effort) {
        List<String> cmd = baseCommand();
        applyBackendAndModel(cmd, model);
        cmd.add("--no-tools");
        cmd.add("--thinking");
        cmd.add(reasonThinkingLevel(maxTurns, effort));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }
        return cmd;
    }

    List<String> buildReasonCommand(String systemPrompt, String model, Integer maxTurns) {
        return buildReasonCommand(systemPrompt, model, maxTurns, null);
    }

    /**
     * Builds the pi CLI command for an action call.
     * System prompt is appended (block instructions on top of pi defaults).
     * Tool restrictions: when {@code allowedTools} is non-empty, only those tools are passed
     * via {@code --tools}; otherwise pi's built-in tools (read/write/edit/bash) remain available.
     */
    List<String> buildActionCommand(String model, String systemPrompt,
                                    List<String> allowedTools, List<String> disallowedTools,
                                    String effort) {
        List<String> cmd = baseCommand();
        applyBackendAndModel(cmd, model);
        // Only an explicit effort emits --thinking here — unset keeps pi's own default
        // (unlike reasoning, where the maxTurns heuristic always picks a level).
        ReasoningEffort.parse(effort).ifPresent(e -> {
            cmd.add("--thinking");
            cmd.add(e.cliValue());
        });
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }
        if (allowedTools != null && !allowedTools.isEmpty()) {
            cmd.add("--tools");
            cmd.add(String.join(",", allowedTools));
        }
        // Pi has no --disallowed-tools flag. If a denylist is provided we log a warning but do
        // not break the call — callers should use allowedTools (whitelist) for hard restrictions.
        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            log.warn("Pi CLI does not support --disallowed-tools; ignoring denylist: {}", disallowedTools);
        }
        return cmd;
    }

    List<String> buildActionCommand(String model, String systemPrompt,
                                    List<String> allowedTools, List<String> disallowedTools) {
        return buildActionCommand(model, systemPrompt, allowedTools, disallowedTools, null);
    }

    /**
     * Splits a workflow {@code model} string of the form {@code "<backend>/<model-id>"} into its parts.
     * If the string has no slash, the configured default backend is used and the whole string is treated as model id.
     * If the string is null/blank, returns null (caller should omit both flags so pi falls back to its own default).
     */
    BackendModel parseModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        int slash = model.indexOf('/');
        if (slash < 0) {
            return new BackendModel(config.getDefaultBackendProvider(), model);
        }
        String backend = model.substring(0, slash);
        String modelId = model.substring(slash + 1);
        if (backend.isBlank() || modelId.isBlank()) {
            return new BackendModel(config.getDefaultBackendProvider(), model);
        }
        return new BackendModel(backend, modelId);
    }

    private List<String> baseCommand() {
        // --no-session: ephemeral, don't persist or branch sessions.
        // --no-context-files: skip AGENTS.md auto-discovery — MySuricates injects its own system prompts
        //                     to keep behavior deterministic across hosts.
        return new ArrayList<>(List.of("pi", "-p", "--no-session", "--no-context-files"));
    }

    private void applyBackendAndModel(List<String> cmd, String model) {
        BackendModel parsed = parseModel(model);
        if (parsed == null) {
            return;
        }
        cmd.add("--provider");
        cmd.add(parsed.backend());
        cmd.add("--model");
        cmd.add(parsed.modelId());
    }

    /** The {@code --thinking} level of a reasoning call ({@link PiThinkingLevel}). */
    String reasonThinkingLevel(Integer maxTurns, String effort) {
        return PiThinkingLevel.forReasoning(maxTurns, effort, config.getDefaultThinkingLevel());
    }

    // --- Process execution (mirrors HostClaudeService) ---

    private record ProcessResult(String text, int exitCode) {}

    private ProcessResult executeProcess(List<String> cmd, String prompt, String workingDirectory,
                                          Integer timeoutSeconds, String label, boolean priority) throws IOException {
        acquireThrottle(priority);
        try {
            ProcessBuilder pb = ChildProcessEnv.forAgent(cmd, workingDirectory);

            log.debug("{} (prompt length: {} chars, workDir: {})", label, prompt.length(), workingDirectory);
            Process proc = pb.start();
            writePromptToStdin(proc, prompt);

            int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : config.getTimeoutSeconds();
            String rawOutput = ProcessDrain.drain(proc, effectiveTimeout, label, LiveOutput.current().rawLines());

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                log.warn("{} returned exit code {}: {}", label, exitCode, truncate(rawOutput, 500));
            }
            return new ProcessResult(rawOutput, exitCode);
        } finally {
            callSemaphore.release();
            if (!priority) {
                backgroundSemaphore.release();
            }
        }
    }

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
            throw new IOException("Interrupted while waiting for pi CLI throttle permit", e);
        }
        long delayMs = config.getInterCallDelayMs();
        if (delayMs > 0) {
            long remaining = delayMs - (System.currentTimeMillis() - lastCallTimestamp.get());
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    callSemaphore.release();
                    if (!priority) {
                        backgroundSemaphore.release();
                    }
                    throw new IOException("Interrupted during pi CLI throttle delay", e);
                }
            }
        }
        lastCallTimestamp.set(System.currentTimeMillis());
    }

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
