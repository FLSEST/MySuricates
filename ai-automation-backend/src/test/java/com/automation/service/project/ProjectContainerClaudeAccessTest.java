package com.automation.service.project;

import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.model.Bind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectContainerClaudeAccessTest {

    private static final String TOKEN = "sk-ant-oat01-" + "a".repeat(60);

    @TempDir
    Path tmp;

    private final Map<String, String> env = new HashMap<>();
    private HostProcessUtils processUtils;
    private Path home;

    @BeforeEach
    void setUp() throws IOException {
        processUtils = mock(HostProcessUtils.class);
        home = Files.createDirectories(tmp.resolve("home"));
        Files.createDirectories(home.resolve(".local/bin"));
        Files.writeString(home.resolve(".local/bin/claude"), "#!/bin/sh\n");
    }

    private ProjectContainerClaudeAccess.Access access() {
        return new ProjectContainerClaudeAccess(processUtils, env::get, home).forContainer(home.toString());
    }

    private static List<String> binds(ProjectContainerClaudeAccess.Access a) {
        return a.binds().stream().map(Bind::toString).toList();
    }

    private void aiEnv(String content) throws IOException {
        Files.createDirectories(home.resolve(".config/mysuricates"));
        Files.writeString(home.resolve(".config/mysuricates/ai.env"), content);
    }

    @Test
    void hostBackendDefaultsToLoginWithTokenFromAiEnvAndNothingFromTheHostConfig() throws IOException {
        aiEnv("OTHER=1\nCLAUDE_CODE_OAUTH_TOKEN=\"" + TOKEN + "\"\n");

        ProjectContainerClaudeAccess.Access a = access();

        assertThat(binds(a)).containsExactly(home + "/.local/bin/claude:/home/agent/.local/bin/claude:ro");
        assertThat(binds(a)).noneMatch(b -> b.contains("/.claude:") || b.contains(".claude.json") || b.contains(".local/share"));
        assertThat(a.env()).containsExactly("CLAUDE_CONFIG_DIR=/tmp/claude-config", "DISABLE_AUTOUPDATER=1",
                "CLAUDE_CODE_OAUTH_TOKEN=" + TOKEN);
    }

    @Test
    void theInstancesOwnTokenWinsOverTheFile() throws IOException {
        aiEnv("CLAUDE_CODE_OAUTH_TOKEN=" + "b".repeat(50) + "\n");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN);

        assertThat(access().env()).contains("CLAUDE_CODE_OAUTH_TOKEN=" + TOKEN);
    }

    @Test
    void anImplausibleTokenIsNotPassedOn() throws IOException {
        aiEnv("CLAUDE_CODE_OAUTH_TOKEN=<token>\n");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", "…");

        assertThat(access().env()).noneMatch(e -> e.startsWith("CLAUDE_CODE_OAUTH_TOKEN="));
    }

    @Test
    void xdgConfigHomeIsWhereTheFileIs() throws IOException {
        Path xdg = Files.createDirectories(tmp.resolve("xdg/mysuricates"));
        Files.writeString(xdg.resolve("ai.env"), "CLAUDE_CODE_OAUTH_TOKEN=" + TOKEN + "\n");
        env.put("XDG_CONFIG_HOME", tmp.resolve("xdg").toString());

        assertThat(access().env()).contains("CLAUDE_CODE_OAUTH_TOKEN=" + TOKEN);
    }

    @Test
    void programsModeSharesNoLogin() throws IOException {
        aiEnv("CLAUDE_CODE_OAUTH_TOKEN=" + TOKEN + "\n");
        env.put("MYSURICATES_AI_FROM_HOST", "programs");

        ProjectContainerClaudeAccess.Access a = access();

        assertThat(binds(a)).hasSize(1);
        assertThat(a.env()).containsExactly("CLAUDE_CONFIG_DIR=/tmp/claude-config", "DISABLE_AUTOUPDATER=1");
    }

    @Test
    void allModeKeepsTheOldMountsBecauseTheInstanceItselfGotAll() {
        env.put("MYSURICATES_AI_FROM_HOST", "all");

        ProjectContainerClaudeAccess.Access a = access();

        assertThat(binds(a)).contains(home + "/.claude:/home/agent/.claude:rw", home + "/.claude.json:/home/agent/.claude.json:rw");
        assertThat(a.env()).isEmpty();
    }

    @Test
    void aSelectionWithoutClaudeGivesNothing() {
        env.put("MYSURICATES_AI_PROVIDERS", "codex, pi");

        assertThat(access()).isEqualTo(ProjectContainerClaudeAccess.Access.NONE);
    }

    @Test
    void missingProgramOnTheHostIsNotBound() throws IOException {
        Files.delete(home.resolve(".local/bin/claude"));

        assertThat(access().binds()).isEmpty();
    }

    /** A container instance cannot see host paths; it has claude exactly when the generator mounted it. */
    @Test
    void containerInstanceJudgesTheProgramByItsOwnPath() throws IOException {
        Files.delete(home.resolve(".local/bin/claude"));
        env.put("MYSURICATES_RUNTIME", "container");
        when(processUtils.isOnPath("claude")).thenReturn(true);

        assertThat(binds(access())).containsExactly(home + "/.local/bin/claude:/home/agent/.local/bin/claude:ro");
    }
}
