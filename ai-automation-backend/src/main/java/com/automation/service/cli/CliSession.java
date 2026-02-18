package com.automation.service.cli;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Slf4j
public class CliSession implements Closeable {

    public record CommandResult(String output, int exitCode) {}

    public record EnrichedCommandResult(String output, int exitCode, ClaudeJsonResponse parsed) {
        public CommandResult toCommandResult() {
            return new CommandResult(output, exitCode);
        }
    }

    private static final Pattern DEFAULT_PROMPT_PATTERN = Pattern.compile(
            "\\$\\s*$|^>\\s*$|^claude>?\\s*$|^codex>?\\s*$|waiting for input",
            Pattern.MULTILINE
    );
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    @Getter
    private final String containerId;
    @Getter
    private final String cliType;
    private final boolean oneOffMode;
    private final Pattern promptPattern;

    private Process process;
    private BufferedReader reader;
    private PrintWriter writer;
    private ExecutorService readExecutor;

    @Getter
    private Instant lastActivity;
    @Getter
    private volatile boolean alive;

    public CliSession(String containerId, String cliType) {
        this(containerId, cliType, null);
    }

    public CliSession(String containerId, String cliType, Pattern customPromptPattern) {
        this.containerId = containerId;
        this.cliType = cliType;
        this.oneOffMode = true;
        this.promptPattern = customPromptPattern != null ? customPromptPattern : DEFAULT_PROMPT_PATTERN;
        this.lastActivity = Instant.now();
    }

    public void start() throws IOException {
        if (oneOffMode) {
            // Bash: no persistent process, commands run via docker exec per call
            alive = true;
            log.info("CLI session started (one-off mode): {} in container {}", cliType, containerId);
            return;
        }

        // Interactive mode for Claude and other CLIs
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "exec", "-i", containerId, cliType
        );
        pb.redirectErrorStream(true);

        process = pb.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
        readExecutor = Executors.newSingleThreadExecutor();
        alive = true;

        // Wait for initial prompt
        readUntilPrompt();

        log.info("CLI session started: {} in container {}", cliType, containerId);
    }

    public CommandResult sendCommand(String command) throws IOException {
        if ("claude".equals(cliType) && oneOffMode) {
            return sendCommandEnriched(command).toCommandResult();
        }

        if (!alive) {
            throw new IllegalStateException("CLI session is not alive");
        }

        lastActivity = Instant.now();

        if (oneOffMode) {
            return executeOneOff(command);
        }

        writer.println(command);
        writer.flush();

        String response = readUntilPrompt();
        lastActivity = Instant.now();

        return new CommandResult(response, 0);
    }

    public EnrichedCommandResult sendCommandEnriched(String command) throws IOException {
        if (!alive) {
            throw new IllegalStateException("CLI session is not alive");
        }

        lastActivity = Instant.now();

        if (!oneOffMode || !"claude".equals(cliType)) {
            CommandResult basic = oneOffMode ? executeOneOff(command) : sendCommand(command);
            return new EnrichedCommandResult(basic.output(), basic.exitCode(), null);
        }

        return executeOneOffEnriched(command);
    }

    private CommandResult executeOneOff(String command) throws IOException {
        List<String> cmd;
        if ("claude".equals(cliType)) {
            // Use stream-json for Claude and extract text
            EnrichedCommandResult enriched = executeOneOffEnriched(command);
            return enriched.toCommandResult();
        } else {
            cmd = List.of("docker", "exec", containerId,
                    "bash", "-c", command);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        try {
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            boolean finished = proc.waitFor(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Command timed out after " + READ_TIMEOUT.toMinutes() + " minutes");
            }
            int exitCode = proc.exitValue();
            lastActivity = Instant.now();
            return new CommandResult(output, exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Command interrupted");
        }
    }

    private EnrichedCommandResult executeOneOffEnriched(String command) throws IOException {
        List<String> cmd = List.of("docker", "exec", containerId,
                "claude", "-p", command,
                "--output-format", "stream-json",
                "--dangerously-skip-permissions");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        try {
            String rawOutput = new String(proc.getInputStream().readAllBytes()).trim();
            boolean finished = proc.waitFor(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Command timed out after " + READ_TIMEOUT.toMinutes() + " minutes");
            }
            int exitCode = proc.exitValue();
            lastActivity = Instant.now();

            ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(rawOutput);
            String textOutput = parsed.result() != null ? parsed.result() : rawOutput;
            return new EnrichedCommandResult(textOutput, exitCode, parsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Command interrupted");
        }
    }

    public String readUntilPrompt() throws IOException {
        Future<String> future = readExecutor.submit(() -> {
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (isPromptLine(line)) {
                    break;
                }
                output.append(line).append("\n");
            }

            if (line == null) {
                alive = false;
            }

            return output.toString().trim();
        });

        try {
            return future.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            alive = false;
            throw new IOException("CLI read timed out after " + READ_TIMEOUT.toMinutes() + " minutes");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("CLI read failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CLI read interrupted");
        }
    }

    public boolean isIdle(Duration timeout) {
        return Duration.between(lastActivity, Instant.now()).compareTo(timeout) > 0;
    }

    private boolean isPromptLine(String line) {
        return promptPattern.matcher(line).find();
    }

    @Override
    public void close() {
        alive = false;

        if (writer != null) {
            try {
                writer.println("exit");
                writer.flush();
            } catch (Exception ignored) {
            }
        }

        if (process != null) {
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }

        if (readExecutor != null) {
            readExecutor.shutdownNow();
        }

        log.info("CLI session closed: {} in container {}", cliType, containerId);
    }
}
