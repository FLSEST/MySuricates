package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
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
     * Uses 'codex exec' with --skip-git-repo-check and output to temp file.
     */
    public String execute(String prompt, Path workingDirectory) throws IOException {
        Path outputFile = Files.createTempFile("codex-result-", ".txt");

        try {
            List<String> cmd = buildCommand(prompt, workingDirectory, outputFile);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }

            log.debug("Host Codex exec (prompt length: {} chars, workDir: {})",
                    prompt.length(), workingDirectory);
            Process proc = pb.start();
            proc.getOutputStream().close();

            try {
                // Drain stdout/stderr to prevent buffer deadlock
                CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                    try { return proc.getInputStream().readAllBytes(); }
                    catch (IOException e) { return new byte[0]; }
                });

                boolean finished = proc.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    throw new IOException("Codex timed out after " + config.getTimeoutSeconds() + " seconds");
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
