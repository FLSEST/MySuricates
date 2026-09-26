package com.automation.service.system;

import com.automation.dto.response.SystemCapabilitiesResponse.ContainerAccessResponse;
import com.automation.dto.response.SystemCapabilitiesResponse.ProviderAccessResponse;
import com.automation.service.project.DockerExpectation;
import com.automation.util.HostProcessUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The startup configuration card: switches, selection, programs — and the logins, which it takes from
 * {@link ProviderLoginCheck}. That the check gives the answer of the AI provider card, with the real
 * login rule, {@code AiProviderStatusServiceTest} shows (7296ad45); here it is a stub.
 */
class ContainerAccessReportTest {

    /** Plausible by length and characters; "secret" in it lets the leak check below find it. */
    private static final String TOKEN = "sk-ant-oat01-secret-value-0123456789abcdefghij";

    private final Map<String, String> env = new HashMap<>();
    /** Logins as the check answers them; a program without an entry is not registered (null). */
    private final Map<String, Boolean> logins = new HashMap<>();
    private HostProcessUtils processUtils;
    private DockerExpectation docker;

    @BeforeEach
    void setUp() {
        processUtils = mock(HostProcessUtils.class);
        docker = mock(DockerExpectation.class);
        when(processUtils.isOnPath(anyString())).thenReturn(true);
    }

    private ContainerAccessResponse report() {
        return new ContainerAccessReport(processUtils, docker, logins::get, env::get).report();
    }

    private static ProviderAccessResponse provider(ContainerAccessResponse r, String id) {
        return r.providers().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void hostRuntimeHasNoSwitchesButTellsTheLoginsLikeTheProviderCard() {
        when(docker.isConfigured()).thenReturn(true);
        logins.putAll(Map.of("claude", true, "codex", false));

        ContainerAccessResponse r = report();

        assertThat(r.runtime()).isEqualTo("host");
        assertThat(r.aiFromHost()).isNull();
        assertThat(r.switchSource()).isNull();
        assertThat(r.switchPrefix()).isNull();
        assertThat(r.dockerSocket()).isTrue();
        assertThat(r.providers()).extracting(ProviderAccessResponse::id).containsExactly("claude", "codex", "pi");
        assertThat(r.providers()).allSatisfy(p -> assertThat(p.selected()).isTrue());
        // Formerly null ("cannot tell") on the host, while the AI provider card next to it knew (7296ad45)
        assertThat(provider(r, "claude").login()).isTrue();
        assertThat(provider(r, "codex").login()).isFalse();
        assertThat(provider(r, "pi").login()).as("no provider with this program registered").isNull();
    }

    @Test
    void containerInLoginModeReportsSelectionProgramsAndLogins() {
        env.putAll(Map.of(
                "MYSURICATES_RUNTIME", "container",
                "MYSURICATES_AI_FROM_HOST", "login",
                "MYSURICATES_AI_PROVIDERS", "claude,codex",
                "MYSURICATES_SWITCH_SOURCE", "/home/u/.config/mysuricates/staging.env",
                "MYSURICATES_SWITCH_PREFIX", "STABLE_"));
        logins.putAll(Map.of("claude", true, "codex", true, "pi", false));
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
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "programs",
                "CLAUDE_CODE_OAUTH_TOKEN", TOKEN, "MYSURICATES_SWITCH_SOURCE", "/srv/staging.env"));
        logins.put("claude", true);

        String json = new ObjectMapper().writeValueAsString(report());

        assertThat(json).doesNotContain("secret");
        assertThat(provider(report(), "claude").login()).isTrue();
    }

    @Test
    void anEmptySelectionMeansAll() {
        env.putAll(Map.of("MYSURICATES_RUNTIME", "container", "MYSURICATES_AI_FROM_HOST", "all",
                "MYSURICATES_AI_PROVIDERS", " "));

        assertThat(report().providers()).allSatisfy(p -> assertThat(p.selected()).isTrue());
    }
}
