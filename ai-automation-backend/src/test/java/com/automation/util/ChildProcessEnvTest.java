package com.automation.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChildProcessEnvTest {

    @Test
    void scrub_removesTheBackendSecrets_evenWhenTheBackendWasStartedWithThem() {
        // A ProcessBuilder copies the backend's environment — in Docker setups that holds the key.
        ProcessBuilder pb = new ProcessBuilder("true");
        pb.environment().put("AUTOMATION_API_KEY", "instance-key");
        pb.environment().put("AUTOMATION_SSL_PASSWORD", "keystore-password");

        ChildProcessEnv.scrub(pb);

        assertThat(pb.environment())
                .doesNotContainKey("AUTOMATION_API_KEY")
                .doesNotContainKey("AUTOMATION_SSL_PASSWORD");
    }

    @Test
    void scrub_keepsTheProviderCredentialsAndOrdinarySettings() {
        Map<String, String> env = new HashMap<>(Map.of(
                "CLAUDE_CODE_OAUTH_TOKEN", "claude-token",
                "ANTHROPIC_API_KEY", "anthropic-key",
                "OPENAI_API_KEY", "openai-key",
                "AUTOMATION_PORT", "9090",
                "PATH", "/usr/bin",
                "AUTOMATION_API_KEY", "instance-key"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsOnlyKeys(
                "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "AUTOMATION_PORT", "PATH");
    }

    @Test
    void forAgent_mergesStderrSetsTheWorkingDirectoryAndScrubs() {
        ProcessBuilder pb = ChildProcessEnv.forAgent(List.of("claude", "-p"), "/tmp");

        assertThat(pb.command()).containsExactly("claude", "-p");
        assertThat(pb.redirectErrorStream()).isTrue();
        assertThat(pb.directory()).isEqualTo(new File("/tmp"));
        assertThat(pb.environment()).doesNotContainKeys("AUTOMATION_API_KEY", "AUTOMATION_SSL_PASSWORD");
    }

    @Test
    void forAgent_leavesTheWorkingDirectoryUnsetWhenBlank() {
        assertThat(ChildProcessEnv.forAgent(List.of("pi"), " ").directory()).isNull();
        assertThat(ChildProcessEnv.forAgent(List.of("pi"), null).directory()).isNull();
    }

    @Test
    void scrub_doesNothingWhenNoSecretIsSet() {
        Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsExactlyEntriesOf(Map.of("PATH", "/usr/bin"));
    }

    @Test
    void scrub_dropsAnInheritedRunToken() {
        // A backend or test suite started from inside a run must not hand that run's token to its own children
        Map<String, String> env = new HashMap<>(Map.of(
                RunAccess.TOKEN_ENV, "parent-run-token", RunAccess.API_ENV, "http://localhost:9090/api/v1", "PATH", "/usr/bin"));

        ChildProcessEnv.scrub(env);

        assertThat(env).containsOnlyKeys("PATH");
    }

    @Test
    void forAgent_putsTheBoundRunTokenIntoTheEnvironment_onlyOnTheRunsThread() {
        RunAccess.bind(new RunAccess.Grant("tok-run", "http://localhost:9090/api/v1"));
        try {
            ProcessBuilder pb = ChildProcessEnv.forAgent(List.of("claude", "-p"), "/tmp");

            assertThat(pb.environment()).containsEntry(RunAccess.TOKEN_ENV, "tok-run")
                    .containsEntry(RunAccess.API_ENV, "http://localhost:9090/api/v1");
        } finally {
            RunAccess.clear();
        }
        assertThat(ChildProcessEnv.forAgent(List.of("claude", "-p"), "/tmp").environment())
                .doesNotContainKeys(RunAccess.TOKEN_ENV, RunAccess.API_ENV);
    }
}
