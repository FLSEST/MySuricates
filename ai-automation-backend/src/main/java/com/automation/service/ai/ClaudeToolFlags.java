package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;

/** Tool-related flags of a Claude CLI action call; split out of HostClaudeService (source-size ratchet). */
final class ClaudeToolFlags {

    private ClaudeToolFlags() {
    }

    static void apply(List<String> cmd, List<String> callAllowedTools, List<String> callDisallowedTools,
                      CliPermissionsConfig permissionsConfig) {
        if (AiProvider.NO_TOOLS.equals(callAllowedTools)) {
            // No built-in tools (compress-ai, dashboard-report assist): the model can only answer
            // from the prompt.
            // --tools "" alone still leaves MCP servers (e.g. plugin context7) reachable and lets
            // SessionStart plugin hooks fire — --strict-mcp-config drops MCP wiring, --setting-sources
            // local keeps only project/local settings (no user/managed hooks). Verified with the real
            // CLI: init event then lists no tools and no mcp servers.
            // Not --bare (TODO edb38a38): that minimal mode also skips keychain reads ("Anthropic auth
            // is strictly ANTHROPIC_API_KEY or apiKeyHelper via --settings"), so on an OAuth-signed-in
            // host the call dies with "Not logged in" — verified on the dev host, see WORKFLOW-GUIDE.
            cmd.add("--tools");
            cmd.add("");
            cmd.add("--strict-mcp-config");
            cmd.add("--setting-sources");
            cmd.add("local");
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

    /**
     * While the module {@code mcp} is on, a user may have registered this instance's own MCP server in the
     * CLI configuration — every AI call of a run could then control the platform. The call then runs
     * without any MCP server of the user's configuration (user, project, plugin and claude.ai connectors;
     * verified with the real CLI: the init event lists none), unless its block opted in
     * ({@code user_mcp_servers}). Applied to the built command, so the builders stay the seam tests replace.
     *
     * @param modules null (hand-built service in tests) counts as module off
     * @return {@code cmd} itself when nothing changes, else a copy with {@code --strict-mcp-config}
     */
    static List<String> isolateFromUserMcpServers(List<String> cmd, ModuleRegistry modules, boolean userMcpServers) {
        if (modules == null || !modules.isEnabled(ModuleCatalog.MCP) || userMcpServers
                || cmd.contains("--strict-mcp-config")) {
            return cmd;
        }
        List<String> isolated = new ArrayList<>(cmd);
        isolated.add("--strict-mcp-config");
        return isolated;
    }
}
