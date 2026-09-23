package com.automation.service.system;

import com.automation.dto.response.SystemCapabilitiesResponse.ContainerAccessResponse;
import com.automation.dto.response.SystemCapabilitiesResponse.ProviderAccessResponse;
import com.automation.service.project.DockerExpectation;
import com.automation.util.HostProcessUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerAccessReportTest {

    @TempDir
    Path tmp;

    /** Plausible by length and characters; "secret" in it lets the leak check below find it. */
    private static final String TOKEN = "sk-ant-oat01-secret-value-0123456789abcdefghij";

    private final Map<String, String> env = new HashMap<>();
    private HostProcessUtils processUtils;
    private DockerExpectation docker;

    @BeforeEach
    void setUp() {
        processUtils = mock(HostProcessUtils.class);
        docker = mock(DockerExpectation.class);
        when(processUtils.isOnPath(anyString())).thenReturn(true);
    }

    private ContainerAccessResponse report() {
        return new ContainerAccessReport(processUtils, docker, env::get, tmp.resolve("home")).report();
    }

    private static ProviderAccessResponse provider(ContainerAccessResponse r, String id) {
        return r.providers().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void hostRuntimeHasNoSwitchesAndCannotTellLogins() {
        when(docker.isConfigured()).thenReturn(true);

        ContainerAccessResponse r = report();

        assertThat(r.runtime()).isEqualTo("host");
        assertThat(r.aiFromHost()).isNull();
        assertThat(r.switchSource()).isNull();
        assertThat(r.switchPrefix()).isNull();
        assertThat(r.dockerSocket()).isTrue();
        assertThat(r.providers()).extracting(ProviderAccessResponse::id).containsExactly("claude", "codex", "pi");
        assertThat(r.providers()).allSatisfy(p -> {
            assertThat(p.selected()).isTrue();
            assertThat(p.login()).isNull();
        });
    }

    @Test
    void containerInLoginModeReportsSelectionProgramsAndLogins() throws IOException {
        Path codexHome = Files.createDirectories(tmp.resolve("providers/codex"));
        Files.writeString(codexHome.resolve("auth.json"), "{}");
        env.putAll(Map.of(
                "MYSURICATES_RUNTIME", "container",
                "MYSURICATES_AI_FROM_HOST", "login",
                "MYSURICATES_AI_PROVIDERS", "claude,codex",
                "MYSURICATES_SWITCH_SOURCE", "/home/u/.config/mysuricates/staging.env",
                "MYSURICATES_SWITCH_PREFIX", "STABLE_",
                "CLAUDE_CODE_OAUTH_TOKEN", TOKEN,
                "CODEX_HOME", codexHome.toString(),
                "PI_CODING_AGENT_DIR", tmp.resolve("providers/pi").toString()));
        when(processUtils.isOnPath("pi")).thenReturn(false);

        ContainerAccessResponse r = report();

        assertThat(r.runtime()).isEqualTo("container");
        assertThat(r.aiFromHost()).isEqualTo("login");
        assertThat(r.switchSource()).isEqualTo("/home/u/.config/mysuricates/staging.env");
        assertThat(r.switchPrefix()).isEqualTo("STABLE_");
        assertThat(provider(r, "claude")).isEqualTo(new ProviderAccessResponse("claude", true, true, true));
        assertThat(provider(r, "codex")).isEqualTo(new ProviderAccessResponse("codex", true, true, true));
        assertThat(provider(r, "pi")).isEqualTo(new ProviderAccessResponse("pi", false, false, false));
    }

    @Test
    void neverCarriesATokenOrAFileContent() throws Exception {
        Path claudeDir = Files.createDirectories(tmp.resolve("providers/claude"));
        Files.writeString(claudeDir.resolve(".credentials.json"), "{\"claudeAiOauth\":\"file-secret\"}");
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "programs",
                "CLAUDE_CODE_OAUTH_TOKEN", TOKEN, "CLAUDE_CONFIG_DIR", claudeDir.toString()));

        String json = new ObjectMapper().writeValueAsString(report());

        assertThat(json).doesNotContain("secret");
        assertThat(provider(report(), "claude").login()).isTrue();
    }

    @Test
    void aPlaceholderTokenIsNoLogin() {
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "login",
                "CLAUDE_CONFIG_DIR", tmp.resolve("providers/claude").toString()));

        for (String placeholder : new String[]{"<token>", "…", "sk-ant-oat-secret-value"}) {
            env.put("CLAUDE_CODE_OAUTH_TOKEN", placeholder);
            assertThat(provider(report(), "claude").login()).as(placeholder).isFalse();
        }
        env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN);
        assertThat(provider(report(), "claude").login()).isTrue();
    }

    @Test
    void ownLoginInProgramsModeIsFoundInThePrivateDirectory() throws IOException {
        Path claudeDir = Files.createDirectories(tmp.resolve("providers/claude"));
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "programs",
                "CLAUDE_CONFIG_DIR", claudeDir.toString()));
        assertThat(provider(report(), "claude").login()).isFalse();

        Files.writeString(claudeDir.resolve(".credentials.json"), "{}");
        assertThat(provider(report(), "claude").login()).isTrue();
    }

    @Test
    void allModeUsesTheHomeDirectoriesAndAnEmptySelectionMeansAll() throws IOException {
        Path piAgent = Files.createDirectories(tmp.resolve("home/.pi/agent"));
        Files.writeString(piAgent.resolve("auth.json"), "{}");
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "all",
                "MYSURICATES_AI_PROVIDERS", " "));

        ContainerAccessResponse r = report();

        assertThat(r.providers()).allSatisfy(p -> assertThat(p.selected()).isTrue());
        assertThat(provider(r, "pi").login()).isTrue();
        assertThat(provider(r, "codex").login()).isFalse();
    }
}
