package com.automation.service.cli;

import com.automation.config.TaskCommandConfig;
import com.automation.util.HostProcessUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the local Taskwarrior {@code task} CLI.
 *
 * <p>Runs on the host via {@link ProcessBuilder} with an explicit argument array — never
 * {@code bash -c} — so caller-supplied values cannot be interpreted by a shell (no injection).
 * Output capture and timeout handling are delegated to {@link HostProcessUtils#drain}.
 *
 * <p>The {@code task} binary always operates on the Taskwarrior data of the host the backend
 * runs on. {@code TASKDATA}/{@code TASKRC} can be overridden per call via the {@code env} map.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskCommandService {

    /**
     * {@code rc.*} overrides forced on every invocation: non-interactive (no confirmation prompts),
     * JSON array output for {@code export}, no user hooks, no color, and silent verbosity so writes
     * don't emit decorative chatter. These are passed as fixed arguments, never from user input.
     */
    private static final List<String> RC_OVERRIDES = List.of(
            "rc.confirmation=off",
            "rc.recurrence.confirmation=off",
            "rc.bulk=0",
            "rc.json.array=on",
            "rc.hooks=off",
            "rc.color=off",
            "rc.verbose=nothing"
    );

    private final TaskCommandConfig config;
    private final HostProcessUtils hostProcessUtils;
    private final ObjectMapper objectMapper;

    public record TaskResult(String output, int exitCode) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code task <rc-overrides> <args...>} on the host.
     *
     * @param args            Taskwarrior arguments (already validated by the caller)
     * @param env             extra env vars (e.g. {@code TASKDATA}/{@code TASKRC}); may be {@code null} or empty
     * @param timeoutOverride per-call timeout in seconds, or {@code null} to use the configured default
     */
    public TaskResult run(List<String> args, Map<String, String> env, Integer timeoutOverride) {
        List<String> cmd = new ArrayList<>(RC_OVERRIDES.size() + args.size() + 1);
        cmd.add("task");
        cmd.addAll(RC_OVERRIDES);
        cmd.addAll(args);

        int timeout = (timeoutOverride != null && timeoutOverride > 0)
                ? timeoutOverride : config.getTimeoutSeconds();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (env != null) {
            env.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    pb.environment().put(k, v);
                }
            });
        }

        String label = "task " + (args.isEmpty() ? "" : args.get(0));
        try {
            Process proc = pb.start();
            String output = hostProcessUtils.drain(proc, timeout, label);
            int exitCode = proc.exitValue();
            log.debug("{} -> exit {}", label, exitCode);
            return new TaskResult(output, exitCode);
        } catch (IOException e) {
            // Includes "Cannot run program "task"" when Taskwarrior is not installed on the host.
            throw new IllegalStateException("Taskwarrior command failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code task <filterTokens> export} and parses the JSON array output into a list of maps.
     * Returns an empty list when Taskwarrior produces no/blank output (e.g. no matching tasks).
     */
    public List<Map<String, Object>> exportJson(List<String> filterTokens, Map<String, String> env) {
        List<String> args = new ArrayList<>(filterTokens.size() + 1);
        args.addAll(filterTokens);
        args.add("export");

        TaskResult result = run(args, env, null);
        if (!result.isSuccess()) {
            throw new IllegalStateException(
                    "Taskwarrior export failed (exit " + result.exitCode() + "): " + result.output());
        }
        String out = result.output();
        if (out == null || out.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(out, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Taskwarrior export JSON: " + e.getMessage(), e);
        }
    }
}
