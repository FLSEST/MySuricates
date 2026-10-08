package com.automation.service.isolation;

import com.automation.config.HostPiConfig;
import com.automation.service.project.ProjectContainerClaudeAccess;
import com.automation.util.HostProcessUtils;
import com.automation.util.UnattendedContainerCommand.Bind;
import com.automation.util.UnattendedContainerCommand.CliAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO 6ee57b87: an unattended container gets the CLI program and exactly one login, nothing else of the home
class UnattendedCliAccessTest {

    @TempDir
    Path home;

    private final HostProcessUtils processUtils = mock(HostProcessUtils.class);
    private final ProjectContainerClaudeAccess claudeAccess = mock(ProjectContainerClaudeAccess.class);
    private final HostPiConfig piConfig = new HostPiConfig();
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void noProgramsByDefault() {
        when(processUtils.findOnPath(anyString())).thenReturn(Optional.empty());
        when(claudeAccess.token()).thenReturn(Optional.empty());
        piConfig.setConfigDir(home.resolve(".pi/agent").toString());
    }

    private UnattendedCliAccess access() {
        return new UnattendedCliAccess(processUtils, claudeAccess, piConfig, Map.of(
                "claude", List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
                "codex", List.of("OPENAI_API_KEY", "CODEX_API_KEY"),
                "pi", List.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY")), env::get, home);
    }

    private Path program(String name, String relative) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "program");
        Path link = Files.createDirectories(home.resolve("bin")).resolve(name);
        Files.createSymbolicLink(link, file);
        when(processUtils.findOnPath(name)).thenReturn(Optional.of(link));
        return file.toRealPath();
    }

    @Test
    @DisplayName("a CLI that is not installed on the host gets nothing")
    void notInstalled() {
        assertThat(access().all()).isEmpty();
    }

    @Test
    @DisplayName("Claude: the binary behind the link, a private config directory and the token for containers")
    void claude() throws IOException {
        Path binary = program("claude", ".local/share/claude/versions/2.1");
        when(claudeAccess.token()).thenReturn(Optional.of("test-token"));

        CliAccess claude = access().all().get("claude");

        assertThat(claude.program()).isEqualTo(binary);
        assertThat(claude.binds()).as("no ~/.claude: settings, hooks and transcripts stay on the host").isEmpty();
        assertThat(claude.environment()).containsEntry("CLAUDE_CONFIG_DIR", "/home/agent/.claude")
                .containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "test-token");
        assertThat(access().claudeLoggedIn()).isTrue();
    }

    @Test
    @DisplayName("Codex: its auth.json read-write in a config directory of its own, the login variable only when set")
    void codex() throws IOException {
        program("codex", "lib/node_modules/@openai/codex/bin/codex.js");
        Path auth = Files.writeString(Files.createDirectories(home.resolve(".codex")).resolve("auth.json"), "{}");
        Files.writeString(home.resolve(".codex/config.toml"), "model = 'x'");
        env.put("OPENAI_API_KEY", "dummy");

        CliAccess codex = access().all().get("codex");

        assertThat(codex.binds()).containsExactly(new Bind(auth.toRealPath(), "/home/agent/.codex/auth.json", false));
        assertThat(codex.privateDirectories()).containsExactly("/home/agent/.codex");
        assertThat(codex.environment()).containsEntry("CODEX_HOME", "/home/agent/.codex")
                .containsEntry("OPENAI_API_KEY", "dummy").doesNotContainKey("CODEX_API_KEY");
    }

    @Test
    @DisplayName("Pi: the auth.json of its configured directory; without one only the variables go along")
    void pi() throws IOException {
        program("pi", "lib/node_modules/@earendil-works/pi-coding-agent/dist/cli.js");

        CliAccess withoutLogin = access().all().get("pi");
        Path auth = Files.writeString(Files.createDirectories(home.resolve(".pi/agent")).resolve("auth.json"), "{}");
        CliAccess withLogin = access().all().get("pi");

        assertThat(withoutLogin.binds()).isEmpty();
        assertThat(withLogin.binds()).containsExactly(new Bind(auth.toRealPath(), "/home/agent/.pi/agent/auth.json", false));
        assertThat(withLogin.environment()).containsEntry("PI_CODING_AGENT_DIR", "/home/agent/.pi/agent");
    }

    @Test
    @DisplayName("without token and API key Claude counts as not logged in")
    void claudeNotLoggedIn() {
        assertThat(access().claudeLoggedIn()).isFalse();
        env.put("ANTHROPIC_API_KEY", "dummy");
        assertThat(access().claudeLoggedIn()).isTrue();
    }
}
