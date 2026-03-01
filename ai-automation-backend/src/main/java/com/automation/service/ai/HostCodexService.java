package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs OpenAI Codex CLI on the host for non-interactive tasks.
 * Analogous to HostClaudeService but for the codex CLI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HostCodexService {

    private final SummarizationConfig config;

    /**
     * Executes a one-shot codex prompt and returns the text result.
     * Uses the default timeout from SummarizationConfig.
     */
    public String execute(String prompt, Path workingDirectory) throws IOException {
        return execute(prompt, workingDirectory, config.getTimeoutSeconds());
    }

    /**
     * Executes a one-shot codex prompt with a custom timeout and returns the text result.
     * Prompt is delivered via stdin to avoid E2BIG errors on large prompts.
     */
    public String execute(String prompt, Path workingDirectory, int timeoutSeconds) throws IOException {
        Path outputFile = Files.createTempFile("codex-result-", ".txt");

        try {
            List<String> cmd = buildCommand(workingDirectory, outputFile);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
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
                // Drain stdout/stderr to prevent buffer deadlock
                CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try { return proc.getInputStream().readAllBytes(); }
                    catch (IOException e) { return new byte[0]; }
                });

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

                // Read result from output file
                if (Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    return Files.readString(outputFile).trim();
                }

                // Fallback: use stdout
                String stdout = new String(outputFuture.get(1, TimeUnit.SECONDS)).trim();
                if (!stdout.isEmpty()) {
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
     * Builds the codex command. Uses "-" as prompt placeholder to read from stdin.
     */
    List<String> buildCommand(Path workingDirectory, Path outputFile) {
        List<String> cmd = new ArrayList<>(List.of(
                "codex", "exec", "-",
                "--skip-git-repo-check",
                "-o", outputFile.toAbsolutePath().toString()
        ));

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
