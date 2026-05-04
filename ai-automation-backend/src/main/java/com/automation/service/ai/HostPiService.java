package com.automation.service.ai;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.HostPiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs Mario Zechner's `pi` coding agent (`@mariozechner/pi-coding-agent`) on the host.
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

    public record ReasoningResult(String text) {}

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
     * Reasoning call: read-only analysis, no tool execution. Uses {@code --no-tools}.
     * The {@code maxTurns} hint is mapped onto pi's {@code --thinking} level: 1 → low, 3 → medium, ≥5 → high.
     */
    public ReasoningResult reason(String prompt, String systemPrompt, String model,
                                   String workingDirectory, Integer maxTurns,
                                   Integer timeoutSeconds) throws IOException {
        List<String> cmd = buildReasonCommand(systemPrompt, model, maxTurns);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host pi reasoning", false);
        return new ReasoningResult(result.text);
    }

    public ActionResult executeAction(String prompt, String model, String systemPrompt,
                                       String workingDirectory, Integer timeoutSeconds,
                                       boolean priority,
                                       List<String> allowedTools,
                                       List<String> disallowedTools) throws IOException {
        List<String> cmd = buildActionCommand(model, systemPrompt, allowedTools, disallowedTools);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host pi action", priority);
        return new ActionResult(result.text, result.exitCode);
    }

    // --- Command builders (package-private for testability) ---

    /**
     * Builds the pi CLI command for a reasoning call.
     * Reasoning is read-only — disable all tools so the model only thinks/responds.
     */
    List<String> buildReasonCommand(String systemPrompt, String model, Integer maxTurns) {
        List<String> cmd = baseCommand();
        applyBackendAndModel(cmd, model);
        cmd.add("--no-tools");
        cmd.add("--thinking");
        cmd.add(thinkingLevelFor(maxTurns));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }
        return cmd;
    }

    /**
     * Builds the pi CLI command for an action call.
     * System prompt is appended (block instructions on top of pi defaults).
     * Tool restrictions: when {@code allowedTools} is non-empty, only those tools are passed
     * via {@code --tools}; otherwise pi's built-in tools (read/write/edit/bash) remain available.
     */
    List<String> buildActionCommand(String model, String systemPrompt,
                                    List<String> allowedTools, List<String> disallowedTools) {
        List<String> cmd = baseCommand();
        applyBackendAndModel(cmd, model);
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

    private String thinkingLevelFor(Integer maxTurns) {
        if (maxTurns == null) {
            return config.getDefaultThinkingLevel();
        }
        if (maxTurns <= 1) return "low";
        if (maxTurns <= 3) return "medium";
        return "high";
    }

    // --- Process execution (mirrors HostClaudeService) ---

    private record ProcessResult(String text, int exitCode) {}

    private ProcessResult executeProcess(List<String> cmd, String prompt, String workingDirectory,
                                          Integer timeoutSeconds, String label, boolean priority) throws IOException {
        acquireThrottle(priority);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                pb.directory(new File(workingDirectory));
            }

            log.debug("{} (prompt length: {} chars, workDir: {})", label, prompt.length(), workingDirectory);
            Process proc = pb.start();
            writePromptToStdin(proc, prompt);

            int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : config.getTimeoutSeconds();
            String rawOutput = drainProcess(proc, effectiveTimeout, label);

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

    private String drainProcess(Process proc, int timeoutSeconds, String label) throws IOException {
        InputStream stdout = proc.getInputStream();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try { return stdout.readAllBytes(); }
            catch (IOException e) { return new byte[0]; }
        });

        try {
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                closeQuietly(stdout);
                throw new IOException(label + " timed out after " + timeoutSeconds + " seconds");
            }
            return new String(outputFuture.get(5, TimeUnit.SECONDS)).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException(label + " interrupted");
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException("Failed to read " + label + " output", e);
        }
    }

    private void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { /* already destroyed */ }
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
