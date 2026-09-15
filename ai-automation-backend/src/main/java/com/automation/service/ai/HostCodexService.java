package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import com.automation.service.live.LiveOutput;
import com.automation.service.system.ApiKeyService;
import com.automation.util.LineListener;
import com.automation.util.ProcessDrain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs OpenAI Codex CLI on the host for non-interactive tasks.
 * Analogous to HostClaudeService but for the codex CLI.
 */
@Service
@Slf4j
public class HostCodexService {

    /**
     * Line prefixes of tool-execution errors that mean Codex' sandbox never started — e.g. bwrap
     * without user namespaces ("bwrap: setting up uid map: Permission denied").
     */
    private static final List<String> SANDBOX_FAILURE_PREFIXES = List.of("bwrap:");

    private final SummarizationConfig config;
    /**
     * Lazily resolved so unit tests that construct this service without a Spring
     * context can keep using the 1-arg constructor. The env var is only injected
     * when the provider is available (production code path via Spring DI).
     */
    private final ObjectProvider<ApiKeyService> apiKeyServiceProvider;

    @Autowired
    public HostCodexService(SummarizationConfig config,
                            ObjectProvider<ApiKeyService> apiKeyServiceProvider) {
        this.config = config;
        this.apiKeyServiceProvider = apiKeyServiceProvider;
    }

    /**
     * Convenience constructor for unit tests that don't wire an {@link ApiKeyService}.
     */
    public HostCodexService(SummarizationConfig config) {
        this(config, null);
    }

    /**
     * Executes a one-shot codex prompt and returns the text result.
     * Uses the default timeout from SummarizationConfig.
     */
    public String execute(String prompt, Path workingDirectory) throws IOException {
        return execute(prompt, workingDirectory, config.getTimeoutSeconds(), false);
    }

    /**
     * Executes a one-shot codex prompt with a custom timeout and returns the text result.
     * Prompt is delivered via stdin to avoid E2BIG errors on large prompts.
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds) throws IOException {
        return execute(prompt, workingDirectory, timeoutSeconds, false);
    }

    /**
     * Executes a one-shot codex prompt with a custom timeout and returns the text result.
     * When readOnly is true, adds --sandbox read-only to prevent file writes (analysis mode).
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds, boolean readOnly) throws IOException {
        return execute(prompt, workingDirectory, timeoutSeconds, readOnly, null);
    }

    /**
     * Executes a one-shot codex prompt with a custom timeout, sandbox mode and model.
     * A blank {@code model} lets codex pick its configured default (~/.codex/config.toml).
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds, boolean readOnly, String model) throws IOException {
        Path outputFile = Files.createTempFile("codex-result-", ".txt");

        try {
            List<String> cmd = buildCommand(workingDirectory, outputFile, readOnly, model);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // Inject the platform API key as an env var so workflows can call the
            // backend REST API (curl -H "X-API-Key: $AUTOMATION_API_KEY ...") without
            // leaking the key into the LLM prompt — and therefore not into the
            // persisted StepResult.output (see H1 finding).
            injectApiKeyEnv(pb);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }

            log.debug("Host Codex exec (prompt length: {} chars, workDir: {}, timeout: {}s)",
                    prompt.length(), workingDirectory, timeoutSeconds);
            Process proc = pb.start();

            // Write prompt to stdin to avoid E2BIG on large prompts
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            try {
                // Drain stdout/stderr to prevent buffer deadlock; lines stream into the live log
                LineListener live = LiveOutput.current().rawLines();
                CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try { return ProcessDrain.readAll(proc.getInputStream(), live); }
                    catch (IOException e) { return new byte[0]; }
                }, ProcessDrain.READERS);

                boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    throw new IOException("Codex timed out after " + timeoutSeconds + " seconds");
                }

                // Wait for stream drain to complete
                outputFuture.get(5, TimeUnit.SECONDS);

                int exitCode = proc.exitValue();
                if (exitCode != 0) {
                    String stderr = new String(outputFuture.get(1, TimeUnit.SECONDS)).trim();
                    log.warn("Codex returned exit code {}: {}", exitCode, truncate(stderr, 500));
                    throw new IOException("Codex failed with exit code " + exitCode + ": " + truncate(stderr, 200));
                }

                String processOutput = new String(outputFuture.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);

                // Read result from output file
                if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    String answer = Files.readString(outputFile).trim();
                    failOnBrokenSandbox(processOutput, answer);
                    return answer;
                }

                // Fallback: use stdout
                String stdout = processOutput.trim();
                if (!stdout.isEmpty()) {
                    failOnBrokenSandbox(stdout, null);
                    return stdout;
                }

                throw new IOException("Codex produced no output");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proc.destroyForcibly();
                throw new IOException("Codex interrupted");
            } catch (ExecutionException | TimeoutException e) {
                proc.destroyForcibly();
                throw new IOException("Failed to read Codex output", e);
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    /**
     * Exit code 0 and an answer do not prove Codex could work: when its sandbox fails to start,
     * every tool call errors and Codex still answers without having read a single file. Such a
     * block must fail instead of passing an answer without codebase on to the next step.
     */
    private static void failOnBrokenSandbox(String processOutput, String answer) throws IOException {
        Optional<String> failure = sandboxFailure(processOutput, answer);
        if (failure.isPresent()) {
            log.warn("Codex sandbox is not working on this host: {}", failure.get());
            throw new IOException("Codex sandbox is not working on this host (" + failure.get()
                    + ") — the block ran without file access. Use provider PI with an openai-codex/… model instead.");
        }
    }

    /**
     * Finds a sandbox start failure in Codex' own process output. Lines that also appear in the final
     * answer are ignored, so a model merely writing about bwrap does not fail the block.
     */
    static Optional<String> sandboxFailure(String processOutput, String answer) {
        if (processOutput == null) {
            return Optional.empty();
        }
        Set<String> answerLines = answer == null ? Set.of()
                : answer.lines().map(String::trim).collect(Collectors.toSet());
        return processOutput.lines()
                .map(String::trim)
                .filter(line -> SANDBOX_FAILURE_PREFIXES.stream().anyMatch(line::startsWith))
                .filter(line -> !answerLines.contains(line))
                .findFirst();
    }

    /**
     * Builds the codex command. Uses "-" as prompt placeholder to read from stdin.
     * When readOnly is true, adds --sandbox read-only to prevent file writes (like Claude's -p).
     */
    List<String> buildCommand(Path workingDirectory, Path outputFile, boolean readOnly) {
        return buildCommand(workingDirectory, outputFile, readOnly, null);
    }

    /**
     * Builds the codex command. Uses "-" as prompt placeholder to read from stdin.
     * When readOnly is true, adds --sandbox read-only; a non-blank model adds {@code -m <model>}.
     */
    List<String> buildCommand(Path workingDirectory, Path outputFile, boolean readOnly, String model) {
        List<String> cmd = new ArrayList<>(List.of(
                "codex", "exec", "-",
                "--skip-git-repo-check",
                "-o", outputFile.toAbsolutePath().toString()
        ));

        if (readOnly) {
            cmd.add("--sandbox");
            cmd.add("read-only");
        }

        if (model != null && !model.isBlank()) {
            cmd.add("-m");
            cmd.add(model.trim());
        }

        if (workingDirectory != null) {
            cmd.add("-C");
            cmd.add(workingDirectory.toAbsolutePath().toString());
        }

        return cmd;
    }

    /**
     * @deprecated Use {@link #buildCommand(Path, Path)} instead. Kept for backward compatibility.
     */
    @Deprecated
    List<String> buildCommand(String prompt, Path workingDirectory, Path outputFile) {
        List<String> cmd = new ArrayList<>(List.of(
                "codex", "exec", prompt,
                "--skip-git-repo-check",
                "-o", outputFile.toAbsolutePath().toString()
        ));

        if (workingDirectory != null) {
            cmd.add("-C");
            cmd.add(workingDirectory.toAbsolutePath().toString());
        }

        return cmd;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Sets {@code AUTOMATION_API_KEY} on the spawned process's environment when
     * an {@link ApiKeyService} is wired (production). Tests that construct the
     * service via the 1-arg constructor get a no-op.
     */
    void injectApiKeyEnv(ProcessBuilder pb) {
        if (apiKeyServiceProvider == null) return;
        ApiKeyService svc = apiKeyServiceProvider.getIfAvailable();
        if (svc == null) return;
        String key = svc.getEffectiveApiKey();
        if (key == null || key.isBlank()) return;
        pb.environment().put("AUTOMATION_API_KEY", key);
    }
}
