package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeToolFlagsTest {

    private static CliPermissionsConfig config(boolean skip, String... allowed) {
        CliPermissionsConfig c = new CliPermissionsConfig();
        c.setSkipPermissions(skip);
        c.setAllowedTools(List.of(allowed));
        return c;
    }

    private static List<String> apply(List<String> allowed, List<String> disallowed, CliPermissionsConfig config) {
        List<String> cmd = new ArrayList<>(List.of("claude", "-p"));
        ClaudeToolFlags.apply(cmd, allowed, disallowed, config);
        return cmd.subList(2, cmd.size());
    }

    @Test
    void noTools_isolatesTheCall_andIgnoresEverythingElse() {
        List<String> flags = apply(AiProvider.NO_TOOLS, List.of("Bash"), config(true, "Read"));

        assertThat(flags).containsExactly("--tools", "", "--strict-mcp-config", "--setting-sources", "local");
        assertThat(flags).as("--bare would break OAuth logins").doesNotContain("--bare");
    }

    @Test
    void callWhitelist_emitsOneFlagPerTool_andDropsSkipPermissions() {
        List<String> flags = apply(List.of("Read", "Grep"), null, config(true, "Bash"));

        assertThat(flags).containsExactly("--allowed-tools", "Read", "--allowed-tools", "Grep");
    }

    @Test
    void noCallWhitelist_fallsBackToGlobalConfig() {
        assertThat(apply(null, null, config(true, "Read", "Edit")))
                .containsExactly("--dangerously-skip-permissions", "--allowedTools", "Read", "--allowedTools", "Edit");
        assertThat(apply(List.of(), List.of(), config(false)))
                .as("empty lists behave like null; no skip flag when disabled")
                .isEmpty();
    }

    @Test
    void disallowedTools_areAppendedInBothModes() {
        assertThat(apply(List.of("Read"), List.of("Bash", "Write"), config(true)))
                .containsExactly("--allowed-tools", "Read", "--disallowed-tools", "Bash", "--disallowed-tools", "Write");
        assertThat(apply(null, List.of("Bash"), config(true)))
                .containsExactly("--dangerously-skip-permissions", "--disallowed-tools", "Bash");
    }
}
