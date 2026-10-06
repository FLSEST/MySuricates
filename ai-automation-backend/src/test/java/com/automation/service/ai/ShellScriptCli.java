package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.HostPiConfig;

import java.util.List;

/**
 * Host services whose CLI is a shell script: the real process, stdin, drain and exit-code handling,
 * without {@code claude} or {@code pi} on the machine — also for tests outside this package, which
 * cannot override the package-private command builders themselves (9cc6c615). Only the reasoning call
 * is scripted; an action call fails with exit code 1, so no test ever starts a real CLI through here.
 */
public final class ShellScriptCli {

    private static final List<String> NO_ACTION = command("ShellScriptCli: no action scripted", 1);

    private ShellScriptCli() {}

    /** A Claude host service whose reasoning call prints {@code output} and ends with {@code exitCode}. */
    public static HostClaudeService claudeReasoning(String output, int exitCode) {
        HostClaudeConfig config = new HostClaudeConfig();
        config.setTimeoutSeconds(30);
        List<String> script = command(output, exitCode);
        return new HostClaudeService(config, new CliPermissionsConfig(), AiCliThrottles.defaults()) {
            @Override
            List<String> buildReasonCommand(String prompt, String systemPrompt, String model,
                                            Integer maxTurns, String effort) {
                return script;
            }

            @Override
            List<String> buildActionCommand(String prompt, String model, String systemPrompt,
                                            List<String> callAllowedTools, List<String> callDisallowedTools,
                                            String effort) {
                return NO_ACTION;
            }
        };
    }

    /** A pi host service whose reasoning call prints {@code output} and ends with {@code exitCode}. */
    public static HostPiService piReasoning(String output, int exitCode) {
        HostPiConfig config = new HostPiConfig();
        config.setTimeoutSeconds(30);
        List<String> script = command(output, exitCode);
        return new HostPiService(config, AiCliThrottles.defaults()) {
            @Override
            List<String> buildReasonCommand(String systemPrompt, String model, Integer maxTurns, String effort) {
                return script;
            }

            @Override
            List<String> buildActionCommand(String model, String systemPrompt, List<String> allowedTools,
                                            List<String> disallowedTools, String effort) {
                return NO_ACTION;
            }
        };
    }

    /** Reads the prompt from stdin first like the CLI does; otherwise writing it can fail with a broken pipe. */
    private static List<String> command(String output, int exitCode) {
        return List.of("/bin/sh", "-c", "cat >/dev/null; printf '%s\\n' \"$1\"; exit " + exitCode, "sh", output);
    }
}
