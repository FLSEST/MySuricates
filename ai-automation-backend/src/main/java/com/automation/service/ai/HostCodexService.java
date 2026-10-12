package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import com.automation.service.live.LiveOutput;
import com.automation.util.ChildProcessEnv;
import com.automation.util.LineListener;
import com.automation.util.ProcessDrain;
import com.automation.util.ProcessTracking;
import lombok.extern.slf4j.Slf4j;
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
import java.util.regex.Pattern;
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
     * Tool-execution errors that mean Codex' sandbox never started — e.g. bwrap without user
     * namespaces ("bwrap: setting up uid map: Permission denied") or without a loopback device
     * ("bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted", TODO 51958d17). Only a line that
     * starts with the marker, or with {@code ERROR}/{@code exec failed} and carries it after a colon,
     * counts: source code, tests and docs that mention bwrap mid-line (review w8) do not.
     */
    private static final Pattern SANDBOX_FAILURE = Pattern.compile("^(?:bwrap:|(?:ERROR|exec failed)\\b.*: bwrap:)");

    private final SummarizationConfig config;
    private final AiCliThrottles throttles;

    public HostCodexService(SummarizationConfig config, AiCliThrottles throttles) {
        this.config = config;
        this.throttles = throttles;
    }

    /**
     * Executes a one-shot codex prompt and returns the text result.
     * Uses the default timeout from SummarizationConfig.
     */
    public String execute(String prompt, Path workingDirectory) throws IOException {
        return execute(prompt, workingDirectory, config.getTimeoutSeconds(), CodexSandbox.WORKSPACE_WRITE, null, false);
    }

    /**
     * Executes a one-shot codex prompt with a custom timeout and returns the text result.
     * Prompt is delivered via stdin to avoid E2BIG errors on large prompts.
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds) throws IOException {
        return execute(prompt, workingDirectory, timeoutSeconds, CodexSandbox.WORKSPACE_WRITE, null, false);
    }

    /**
     * Executes a one-shot codex prompt with timeout, sandbox and model; a blank {@code model} lets codex pick its
     * configured default (~/.codex/config.toml). {@code priority} marks an interactive call (assistant chat),
     * which may use the slots the Codex limit keeps free for it (TODO bd5df8d7).
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds, CodexSandbox sandbox, String model,
                          boolean priority) throws IOException {
        try (var permit = throttles.acquire(AiCliThrottles.CODEX, priority)) {
            return run(prompt, workingDirectory, timeoutSeconds, sandbox == CodexSandbox.READ_ONLY, model);
        }
    }

    private String run(String prompt, Path workingDirectory, int timeoutSeconds, boolean readOnly, String model) throws IOException {
        // A directory of its own: in a container (TODO 6ee57b87) Codex can only write the answer into a mount
        Path outputDir = Files.createTempDirectory("codex-result-");
        Path outputFile = outputDir.resolve("answer.txt");

        try {
            List<String> cmd = buildCommand(workingDirectory, outputFile, readOnly, model);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }
            // The agent may run prompts with foreign content unattended — it gets no
            // instance API key (TODO 62037bf2).
            ChildProcessEnv.isolate(pb, outputDir);

            log.debug("Host Codex exec (prompt length: {} chars, workDir: {}, timeout: {}s)",
                    prompt.length(), workingDirectory, timeoutSeconds);
            Process proc = ChildProcessEnv.start(pb);
            // Registered with the running step (if any), so cancelling the run kills codex
            ProcessTracking.Tracker tracker = ProcessTracking.current();
            tracker.started(proc);

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
                    // The transcript header fills the first 200 chars — name the sandbox failure instead
                    failOnBrokenSandbox(stderr, null, prompt);
                    log.warn("Codex returned exit code {}: {}", exitCode, truncate(stderr, 500));
                    throw new IOException("Codex failed with exit code " + exitCode + ": " + truncate(stderr, 200));
                }

                String processOutput = new String(outputFuture.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);

                // Read result from output file — a plain file only, never a link Codex left there
                Optional<String> answer = CodexAnswerFile.read(outputFile);
                if (answer.isPresent()) {
                    failOnBrokenSandbox(processOutput, answer.get(), prompt);
                    return answer.get();
                }

                // Fallback: use stdout
                String stdout = processOutput.trim();
                if (!stdout.isEmpty()) {
                    failOnBrokenSandbox(stdout, null, prompt);
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
            } finally {
                tracker.finished(proc);
            }
        } finally {
            Files.deleteIfExists(outputFile);
            try {
                Files.deleteIfExists(outputDir);
            } catch (IOException leftover) { // anything else Codex wrote there stays in the temp directory
                log.debug("Could not remove {}: {}", outputDir, leftover.getMessage());
            }
        }
    }

    /**
     * Exit code 0 and an answer do not prove Codex could work: when its sandbox fails to start,
     * every tool call errors and Codex still answers without having read a single file — sometimes
     * with nothing but the error line. Such a block must fail (and is not retried) instead of passing
     * an answer without codebase on to the next step. A non-zero exit gets the same clear message.
     */
    private static void failOnBrokenSandbox(String processOutput, String answer, String prompt) throws IOException {
        Optional<String> failure = sandboxFailure(processOutput, answer, prompt);
        if (failure.isPresent()) {
            log.warn("Codex sandbox could not start on this host: {}", failure.get());
            throw new IOException("Codex sandbox could not start on this host (" + failure.get()
                    + ") — the step fails instead of passing on an answer without file access; it is not retried."
                    + " If this keeps happening, use provider PI with an openai-codex/… model instead.");
        }
    }

    /**
     * Finds a sandbox start failure in Codex' own process output. Lines that also appear in the
     * final answer or in the prompt are ignored, so a model merely writing about bwrap — or a prompt
     * quoting the error — does not fail the block. An answer that consists of nothing but sandbox
     * failure lines is a failure itself, quoted or not.
     */
    static Optional<String> sandboxFailure(String processOutput, String answer, String prompt) {
        Set<String> ignored = new java.util.HashSet<>(nonBlankLines(answer));
        ignored.addAll(nonBlankLines(prompt));
        Optional<String> inOutput = processOutput == null ? Optional.empty() : processOutput.lines()
                .map(String::trim)
                .filter(HostCodexService::isSandboxFailureLine)
                .filter(line -> !ignored.contains(line))
                .findFirst();
        if (inOutput.isPresent()) {
            return inOutput;
        }
        List<String> answerLines = List.copyOf(nonBlankLines(answer));
        if (!answerLines.isEmpty() && answerLines.stream().allMatch(HostCodexService::isSandboxFailureLine)) {
            return Optional.of(answerLines.get(0));
        }
        return Optional.empty();
    }

    private static boolean isSandboxFailureLine(String line) {
        return SANDBOX_FAILURE.matcher(line).find();
    }

    private static java.util.LinkedHashSet<String> nonBlankLines(String text) {
        return text == null ? new java.util.LinkedHashSet<>() : text.lines().map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
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
}
