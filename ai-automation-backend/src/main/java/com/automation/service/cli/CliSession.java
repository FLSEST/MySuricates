package com.automation.service.cli;

import com.automation.service.cli.claude.ClaudeJsonResponse;
import com.automation.service.cli.claude.ClaudeOutputParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Slf4j
public class CliSession implements Closeable {

    public record CommandResult(String output, int exitCode) {}

    public record EnrichedCommandResult(String output, int exitCode, ClaudeJsonResponse parsed,
                                        Map<String, Object> metadata) {
        /** Result without step metadata (container sessions, tests). */
        public EnrichedCommandResult(String output, int exitCode, ClaudeJsonResponse parsed) {
            this(output, exitCode, parsed, Map.of());
        }

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
    private final boolean skipPermissions;
    private final List<String> allowedTools;

    private Process process;
    private BufferedReader reader;
    private PrintWriter writer;
    private ExecutorService readExecutor;

    @Getter
    private Instant lastActivity;
    @Getter
    private volatile boolean alive;

    public CliSession(String containerId, String cliType) {
        this(containerId, cliType, null, true, List.of());
    }

    public CliSession(String containerId, String cliType, boolean skipPermissions, List<String> allowedTools) {
        this(containerId, cliType, null, skipPermissions, allowedTools);
    }

    public CliSession(String containerId, String cliType, Pattern customPromptPattern,
                       boolean skipPermissions, List<String> allowedTools) {
        this.containerId = containerId;
        this.cliType = cliType;
        this.oneOffMode = true;
        this.promptPattern = customPromptPattern != null ? customPromptPattern : DEFAULT_PROMPT_PATTERN;
        this.skipPermissions = skipPermissions;
        this.allowedTools = allowedTools != null ? allowedTools : List.of();
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
        return sendCommandEnriched(command, null);
    }

    public EnrichedCommandResult sendCommandEnriched(String command, String model) throws IOException {
        return sendCommandEnriched(command, model, null);
    }

    public EnrichedCommandResult sendCommandEnriched(String command, String model, String systemPrompt) throws IOException {
        if (!alive) {
            throw new IllegalStateException("CLI session is not alive");
        }

        lastActivity = Instant.now();

        if (!oneOffMode || !"claude".equals(cliType)) {
            CommandResult basic = oneOffMode ? executeOneOff(command) : sendCommand(command);
            return new EnrichedCommandResult(basic.output(), basic.exitCode(), null);
        }

        return executeOneOffEnriched(command, model, systemPrompt);
    }

    private CommandResult executeOneOff(String command) throws IOException {
        if ("claude".equals(cliType)) {
            EnrichedCommandResult enriched = executeOneOffEnriched(command);
            return enriched.toCommandResult();
        }
        throw new UnsupportedOperationException("Only claude CLI type is supported in one-off mode");
    }

    private EnrichedCommandResult executeOneOffEnriched(String command) throws IOException {
        return executeOneOffEnriched(command, null);
    }

    private EnrichedCommandResult executeOneOffEnriched(String command, String model) throws IOException {
        return executeOneOffEnriched(command, model, null);
    }

    EnrichedCommandResult executeOneOffEnriched(String command, String model, String systemPrompt) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "-i", containerId,
                "claude", "-p",
                "--output-format", "stream-json", "--verbose"));
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }
        if (skipPermissions) {
            cmd.add("--dangerously-skip-permissions");
        }
        for (String tool : allowedTools) {
            cmd.add("--allowedTools");
            cmd.add(tool);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        try {
            // Write prompt via stdin to avoid command-line injection and E2BIG errors
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            }
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
