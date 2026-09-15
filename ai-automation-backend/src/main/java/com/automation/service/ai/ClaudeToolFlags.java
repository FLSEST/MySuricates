package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;

import java.util.List;

/** Tool-related flags of a Claude CLI action call; split out of HostClaudeService (source-size ratchet). */
final class ClaudeToolFlags {

    private ClaudeToolFlags() {
    }

    static void apply(List<String> cmd, List<String> callAllowedTools, List<String> callDisallowedTools,
                      CliPermissionsConfig permissionsConfig) {
        if (AiProvider.NO_TOOLS.equals(callAllowedTools)) {
            // No built-in tools (compress-ai): the model can only answer from the prompt.
            cmd.add("--tools");
            cmd.add("");
            return;
        }
        boolean hasCallWhitelist = callAllowedTools != null && !callAllowedTools.isEmpty();
        if (hasCallWhitelist) {
            // Per-call strict whitelist: drop skip-permissions, let the CLI enforce the list.
            // Other tools (Edit, Write, Bash, …) are unavailable to the model.
            // Emit one --allowed-tools flag per tool, mirroring the global CliPermissionsConfig
            // pattern below — the CLI accepts both space-separated and repeated flag forms, the
            // repeated form is less ambiguous and survives shell-quoting edge cases.
            for (String tool : callAllowedTools) {
                cmd.add("--allowed-tools");
                cmd.add(tool);
            }
        } else {
            if (permissionsConfig.isSkipPermissions()) {
                cmd.add("--dangerously-skip-permissions");
            }
            for (String tool : permissionsConfig.getAllowedTools()) {
                cmd.add("--allowedTools");
                cmd.add(tool);
            }
        }
        if (callDisallowedTools != null && !callDisallowedTools.isEmpty()) {
            for (String tool : callDisallowedTools) {
                cmd.add("--disallowed-tools");
                cmd.add(tool);
            }
        }
    }
}
