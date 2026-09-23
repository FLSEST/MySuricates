package com.automation.service.ai.status;

import com.automation.service.ai.ProviderSetup;
import com.automation.service.ai.status.AiProviderStatusReport.LoginVia;
import com.automation.service.ai.status.ProviderLoginProbe.Login;
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

/** The installer's login rule, seen from the backend (f5a273ae) — scripts/lib/providers.sh is the model. */
class ProviderLoginProbeTest {

    @TempDir
    Path home;

    /** Plausible by length and characters; "secret" in it lets the leak checks find it. */
    private static final String TOKEN = "sk-ant-oat01-secret-value-0123456789abcdefghij";

    private final Map<String, String> env = new HashMap<>();

    private static final ProviderSetup CLAUDE = new ProviderSetup("claude", "install", "claude auth login",
            List.of(".claude/.credentials.json", ".claude.json:oauthAccount"),
            List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"), ".claude", "CLAUDE_CONFIG_DIR");

    private static final ProviderSetup CODEX = new ProviderSetup("codex", "install", "codex login",
            List.of(".codex/auth.json"), List.of("OPENAI_API_KEY"), ".codex", "CODEX_HOME");

    private static final ProviderSetup PI = new ProviderSetup("pi", "install", "pi, then /login",
            List.of(".pi/agent/auth.json:\""), List.of("OPENAI_API_KEY"), ".pi/agent", "PI_CODING_AGENT_DIR");

    private Optional<Login> find(ProviderSetup setup) {
        return ProviderLoginProbe.find(setup, env::get, home);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    void nothingThereIsNoLogin() {
        assertThat(find(CLAUDE)).isEmpty();
        assertThat(find(CODEX)).isEmpty();
        assertThat(find(PI)).isEmpty();
    }

    @Test
    void aSetVariableCountsAndOnlyItsNameComesBack() {
        env.put("OPENAI_API_KEY", "sk-secret-openai");

        Login login = find(CODEX).orElseThrow();

        assertThat(login.via()).isEqualTo(LoginVia.ENVIRONMENT);
        assertThat(login.source()).isEqualTo("OPENAI_API_KEY");
        assertThat(login.toString()).doesNotContain("secret");
    }

    @Test
    void aBlankVariableIsNotSet() {
        env.put("OPENAI_API_KEY", "   ");

        assertThat(find(CODEX)).isEmpty();
    }

    /** 9bef7e8c: a placeholder from a copied instruction must not count as a login. */
    @Test
    void theClaudeTokenMustBePlausible() {
        env.put("CLAUDE_CODE_OAUTH_TOKEN", "<token>");
        assertThat(find(CLAUDE)).isEmpty();

        env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN);
        Login login = find(CLAUDE).orElseThrow();
        assertThat(login.source()).isEqualTo("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(login.toString()).doesNotContain("secret");
    }

    @Test
    void aLoginFileCountsWhenItIsNotEmpty() throws IOException {
        write(".codex/auth.json", "");
        assertThat(find(CODEX)).as("empty, as bash -s").isEmpty();

        write(".codex/auth.json", "{\"tokens\":{}}");
        assertThat(find(CODEX)).contains(new Login(LoginVia.FILE, "~/.codex/auth.json"));
    }

    /** macOS keeps the Claude login in the keychain; then only ~/.claude.json shows the account. */
    @Test
    void aFileWithPatternMustContainIt() throws IOException {
        write(".claude.json", "{\n  \"hasCompletedOnboarding\": true\n}\n");
        assertThat(find(CLAUDE)).isEmpty();

        write(".claude.json", "{\n  \"hasCompletedOnboarding\": true,\n  \"oauthAccount\": {}\n}\n");
        assertThat(find(CLAUDE)).contains(new Login(LoginVia.FILE, "~/.claude.json"));
    }

    /** pi: at least one backend in auth.json — an empty {} holds no quote character. */
    @Test
    void piNeedsAtLeastOneBackend() throws IOException {
        write(".pi/agent/auth.json", "{}");
        assertThat(find(PI)).isEmpty();

        write(".pi/agent/auth.json", "{\"anthropic\":{\"type\":\"oauth\"}}");
        assertThat(find(PI)).contains(new Login(LoginVia.FILE, "~/.pi/agent/auth.json"));
    }

    @Test
    void aVariableWinsOverAFile() throws IOException {
        write(".codex/auth.json", "{\"tokens\":{}}");
        env.put("OPENAI_API_KEY", "sk-secret-openai");

        assertThat(find(CODEX).orElseThrow().via()).isEqualTo(LoginVia.ENVIRONMENT);
    }

    /**
     * Container instances move the directories (scripts/lib/host-mounts.sh): CODEX_HOME points to
     * /app/data/providers/codex, where the login of the machine is mounted. The home directory's copy
     * is then not the one the CLI reads.
     */
    @Test
    void aMovedDirectoryIsWhereTheLoginIsLookedUp() throws IOException {
        Path moved = Files.createDirectories(home.resolve("providers/codex"));
        env.put("CODEX_HOME", moved.toString());
        write(".codex/auth.json", "{\"tokens\":{}}");

        assertThat(find(CODEX)).as("the home copy is not what the CLI reads").isEmpty();

        Files.writeString(moved.resolve("auth.json"), "{\"tokens\":{}}");
        assertThat(find(CODEX)).contains(new Login(LoginVia.FILE, "$CODEX_HOME/auth.json"));
    }

    @Test
    void aMovedClaudeDirectoryHoldsTheCredentials() throws IOException {
        Path moved = Files.createDirectories(home.resolve("providers/claude"));
        env.put("CLAUDE_CONFIG_DIR", moved.toString());
        Files.writeString(moved.resolve(".credentials.json"), "{\"claudeAiOauth\":{}}");

        assertThat(find(CLAUDE)).contains(new Login(LoginVia.FILE, "$CLAUDE_CONFIG_DIR/.credentials.json"));
    }

    @Test
    void aSetupWithoutMovableDirectoryLooksInHomeOnly() throws IOException {
        ProviderSetup plain = new ProviderSetup("tool", "install", "tool login",
                List.of(".tool/auth.json"), List.of());
        env.put("TOOL_HOME", home.resolve("elsewhere").toString());
        write(".tool/auth.json", "x");

        assertThat(ProviderLoginProbe.find(plain, env::get, home))
                .contains(new Login(LoginVia.FILE, "~/.tool/auth.json"));
    }
}
