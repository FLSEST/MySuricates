package com.automation.service.ai.status;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.ProviderSetup;
import com.automation.service.ai.status.AiProviderStatusReport.LoginVia;
import com.automation.service.ai.status.AiProviderStatusReport.ProviderStatus;
import com.automation.service.ai.status.AiProviderStatusReport.State;
import com.automation.dto.response.SystemCapabilitiesResponse.ProviderAccessResponse;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.ContainerAccessReport;
import com.automation.util.HostProcessUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Status per AI provider for the start page and the settings (f5a273ae): states, commands, cache. */
class AiProviderStatusServiceTest {

    @TempDir
    Path home;

    /** Plausible by length and characters; "secret" in it lets the leak check find it. */
    private static final String TOKEN = "sk-ant-oat01-secret-value-0123456789abcdefghij";

    private final Map<String, String> env = new HashMap<>();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private HostProcessUtils processUtils;
    private AiProviderRegistry registry;
    private List<AiProvider> providers;

    @BeforeEach
    void setUp() {
        env.put("HOME", home.toString());
        processUtils = mock(HostProcessUtils.class);
        when(processUtils.isOnPath("claude")).thenReturn(true);
        when(processUtils.isOnPath("codex")).thenReturn(true);
        when(processUtils.isOnPath("pi")).thenReturn(false);
        registry = mock(AiProviderRegistry.class);
        when(registry.getDefaultProviderId()).thenReturn("CLAUDE");
        // registration order on purpose not alphabetical: the report sorts by id
        providers = List.of(
                provider("PI", new ProviderSetup("pi", "npm install -g pi", "pi",
                        List.of(".pi/agent/auth.json:\""), List.of("GEMINI_API_KEY"), ".pi/agent", "PI_CODING_AGENT_DIR",
                        "pi_slash_login")),
                provider("CLAUDE", new ProviderSetup("claude", "curl claude", "claude auth login",
                        List.of(".claude/.credentials.json"), List.of("CLAUDE_CODE_OAUTH_TOKEN"), ".claude", "CLAUDE_CONFIG_DIR")),
                provider("CODEX", new ProviderSetup("codex", "npm install -g codex", "codex login",
                        List.of(".codex/auth.json"), List.of("OPENAI_API_KEY"), ".codex", "CODEX_HOME")));
    }

    private static AiProvider provider(String id, ProviderSetup setup) {
        AiProvider provider = mock(AiProvider.class);
        when(provider.getId()).thenReturn(id);
        when(provider.setup()).thenReturn(setup);
        return provider;
    }

    private AiProviderStatusService service(String instanceContainer, int ttlSeconds) {
        return new AiProviderStatusService(providers, registry, processUtils, env::get, instanceContainer,
                ttlSeconds, now::get);
    }

    private static ProviderStatus provider(AiProviderStatusReport report, String id) {
        return report.providers().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachProviderGetsItsStateAndTheCommandsFromItsSetup() {
        env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN);

        AiProviderStatusReport report = service("", 60).report(false);

        assertThat(report.container()).isFalse();
        assertThat(report.providers()).extracting(ProviderStatus::id).containsExactly("CLAUDE", "CODEX", "PI");

        ProviderStatus claude = provider(report, "CLAUDE");
        assertThat(claude.state()).isEqualTo(State.READY);
        assertThat(claude.installed()).isTrue();
        assertThat(claude.loggedIn()).isTrue();
        assertThat(claude.loginVia()).isEqualTo(LoginVia.ENVIRONMENT);
        assertThat(claude.loginSource()).isEqualTo("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(claude.defaultProvider()).isTrue();

        ProviderStatus codex = provider(report, "CODEX");
        assertThat(codex.state()).isEqualTo(State.LOGIN_MISSING);
        assertThat(codex.loggedIn()).isFalse();
        assertThat(codex.loginVia()).isNull();
        assertThat(codex.loginSource()).isNull();
        assertThat(codex.login()).isEqualTo("codex login");
        assertThat(codex.loginNote()).as("the command says it all").isNull();
        assertThat(codex.defaultProvider()).isFalse();

        ProviderStatus pi = provider(report, "PI");
        assertThat(pi.state()).isEqualTo(State.NOT_INSTALLED);
        assertThat(pi.cli()).isEqualTo("pi");
        assertThat(pi.install()).isEqualTo("npm install -g pi");
        // Only the command; what follows comes as a key the interface translates (eab43a8b)
        assertThat(pi.login()).isEqualTo("pi");
        assertThat(pi.loginNote()).isEqualTo("pi_slash_login");
    }

    /** A key in the environment without the program: still not installed, the login is kept for later. */
    @Test
    void aLoginWithoutTheProgramIsStillNotInstalled() {
        env.put("GEMINI_API_KEY", "gemini-secret");

        ProviderStatus pi = provider(service("", 60).report(false), "PI");

        assertThat(pi.state()).isEqualTo(State.NOT_INSTALLED);
        assertThat(pi.loggedIn()).isTrue();
    }

    @Test
    void theAnswerIsCachedUntilTheTtlRunsOut() throws IOException {
        AiProviderStatusService service = service("", 60);
        AiProviderStatusReport first = service.report(false);
        assertThat(first.checkedAt()).isEqualTo(Instant.ofEpochMilli(1_000_000L));

        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/auth.json"), "{\"tokens\":{}}");
        now.addAndGet(59_000);
        AiProviderStatusReport cached = service.report(false);

        assertThat(provider(cached, "CODEX").state()).as("still the cached answer").isEqualTo(State.LOGIN_MISSING);
        assertThat(cached.checkedAt()).isEqualTo(first.checkedAt());
        verify(processUtils, times(1)).isOnPath("codex");

        now.addAndGet(1_000);
        AiProviderStatusReport fresh = service.report(false);

        assertThat(provider(fresh, "CODEX").state()).isEqualTo(State.READY);
        assertThat(fresh.checkedAt()).isEqualTo(Instant.ofEpochMilli(1_060_000L));
        verify(processUtils, times(2)).isOnPath("codex");
    }

    /** Right after `codex login` the person asks again — the cache must not make them wait a minute. */
    @Test
    void refreshLooksAgainAtOnce() throws IOException {
        AiProviderStatusService service = service("", 60);
        assertThat(provider(service.report(false), "CODEX").state()).isEqualTo(State.LOGIN_MISSING);

        Files.createDirectories(home.resolve(".codex"));
        Files.writeString(home.resolve(".codex/auth.json"), "{\"tokens\":{}}");

        assertThat(provider(service.report(true), "CODEX").state()).isEqualTo(State.READY);
        assertThat(provider(service.report(false), "CODEX").state()).as("the refresh fills the cache")
                .isEqualTo(State.READY);
    }

    @Test
    void theDefaultFollowsTheSettingEvenFromTheCache() {
        AiProviderStatusService service = service("", 60);
        assertThat(provider(service.report(false), "CLAUDE").defaultProvider()).isTrue();

        when(registry.getDefaultProviderId()).thenReturn("CODEX");
        AiProviderStatusReport report = service.report(false);

        assertThat(provider(report, "CLAUDE").defaultProvider()).isFalse();
        assertThat(provider(report, "CODEX").defaultProvider()).isTrue();
        verify(processUtils, times(1)).isOnPath("claude");
    }

    @Test
    void aContainerIsTheMarkOfHostMountsOrANamedInstanceContainer() {
        assertThat(service("", 60).report(false).container()).isFalse();
        assertThat(service("mysuricates-tryout", 60).report(false).container()).isTrue();

        env.put("MYSURICATES_RUNTIME", "container");
        assertThat(service("", 60).report(false).container()).isTrue();
    }

    @Test
    void noValueEverLeavesTheService() throws Exception {
        env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN);
        env.put("OPENAI_API_KEY", "sk-secret-openai");
        env.put("GEMINI_API_KEY", "gemini-secret");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/.credentials.json"), "{\"accessToken\":\"secret-file-content\"}");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service("", 60).report(false));

        assertThat(json).doesNotContain("secret");
        assertThat(json).contains("CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "GEMINI_API_KEY");
    }

    /**
     * 7296ad45: the startup configuration card ({@link ContainerAccessReport}) asks this service for the
     * logins instead of its own check — which said "unknown" on the host, took any pi auth.json and knew no
     * API key variable. Both cards must say the same about one machine, case by case.
     */
    @Test
    void theStartupConfigurationCardSaysTheSameAboutEveryLogin() throws IOException {
        AiProviderStatusService service = service("", 60);
        ContainerAccessReport access = new ContainerAccessReport(processUtils, mock(DockerExpectation.class), service);
        Path claudeDir = home.resolve("providers/claude");
        Path codexDir = home.resolve("providers/codex");

        List<Runnable> steps = List.of(
                () -> { },                                                             // nothing anywhere
                () -> env.put("CLAUDE_CODE_OAUTH_TOKEN", "<token>"),                   // placeholder: no login
                () -> env.put("CLAUDE_CODE_OAUTH_TOKEN", TOKEN),
                () -> write(home.resolve(".pi/agent/auth.json"), "{}"),                // pi without a backend
                () -> write(home.resolve(".pi/agent/auth.json"), "{\"anthropic\":{}}"),
                () -> env.put("OPENAI_API_KEY", "sk-openai"),                          // codex via a variable
                () -> {                                                               // moved directories (container)
                    env.clear();
                    env.put("HOME", home.toString());
                    env.put("CLAUDE_CONFIG_DIR", claudeDir.toString());
                    env.put("CODEX_HOME", codexDir.toString());
                    write(home.resolve(".codex/auth.json"), "{}");                     // not where CODEX_HOME points
                },
                () -> {
                    write(claudeDir.resolve(".credentials.json"), "{}");
                    write(codexDir.resolve("auth.json"), "{}");
                });
        List<String> seen = new java.util.ArrayList<>();
        for (Runnable step : steps) {
            step.run();
            AiProviderStatusReport status = service.report(true);
            List<ProviderAccessResponse> card = access.report().providers();
            for (ProviderStatus provider : status.providers()) {
                ProviderAccessResponse same = card.stream().filter(p -> p.id().equals(provider.cli())).findFirst()
                        .orElseThrow();
                assertThat(same.login()).as(provider.cli() + " after step " + seen.size())
                        .isEqualTo(provider.loggedIn());
            }
            seen.add(status.providers().stream().map(p -> p.cli() + "=" + p.loggedIn())
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        // the rule itself, step by step (sorted by id: claude, codex, pi)
        assertThat(seen).containsExactly(
                "claude=false,codex=false,pi=false",
                "claude=false,codex=false,pi=false",
                "claude=true,codex=false,pi=false",
                "claude=true,codex=false,pi=false",
                "claude=true,codex=false,pi=true",
                "claude=true,codex=true,pi=true",
                "claude=false,codex=false,pi=true",
                "claude=true,codex=true,pi=true");
        assertThat(service.loggedIn("gemini")).as("no provider with this program").isNull();
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void homeIsWhereTheClisLookNotWhereTheJvmWasPointed() {
        assertThat(AiProviderStatusService.homeOf(Map.of("HOME", "/home/someone")::get)).isEqualTo(Path.of("/home/someone"));
        assertThat(AiProviderStatusService.homeOf(Map.<String, String>of()::get))
                .isEqualTo(Path.of(System.getProperty("user.home")));
    }
}
