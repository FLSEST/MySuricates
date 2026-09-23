package com.automation.service.system;

import com.automation.dto.response.SystemCapabilitiesResponse.ContainerAccessResponse;
import com.automation.dto.response.SystemCapabilitiesResponse.ProviderAccessResponse;
import com.automation.service.project.DockerExpectation;
import com.automation.util.HostProcessUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * What this instance got from the host for the AI CLIs, for the read-only startup configuration card
 * in the settings (262948a4).
 *
 * <p>The switches live outside the app on purpose: scripts/lib/host-mounts.sh turns them into mounts
 * and environment before the container starts, so neither a workflow nor a leaked API key can widen
 * what the instance sees. This class only reads the environment the generator left behind and checks
 * what is actually there. Only names and yes/no values leave it — never a token, never the content of
 * a login file.
 */
@Component
public class ContainerAccessReport {

    static final List<String> PROVIDERS = List.of("claude", "codex", "pi");

    private final HostProcessUtils processUtils;
    private final DockerExpectation docker;
    private final UnaryOperator<String> env;
    private final Path userHome;

    @Autowired
    public ContainerAccessReport(HostProcessUtils processUtils, DockerExpectation docker) {
        this(processUtils, docker, System::getenv, Path.of(System.getProperty("user.home")));
    }

    ContainerAccessReport(HostProcessUtils processUtils, DockerExpectation docker,
                          UnaryOperator<String> env, Path userHome) {
        this.processUtils = processUtils;
        this.docker = docker;
        this.env = env;
        this.userHome = userHome;
    }

    public ContainerAccessResponse report() {
        boolean container = "container".equals(env.apply("MYSURICATES_RUNTIME"));
        Set<String> selected = container ? selection(env.apply("MYSURICATES_AI_PROVIDERS")) : Set.copyOf(PROVIDERS);
        List<ProviderAccessResponse> providers = PROVIDERS.stream()
                .map(id -> new ProviderAccessResponse(id, selected.contains(id), processUtils.isOnPath(id),
                        container ? loginPresent(id) : null))
                .toList();
        return new ContainerAccessResponse(
                container ? "container" : "host",
                container ? blankToNull(env.apply("MYSURICATES_AI_FROM_HOST")) : null,
                docker.isConfigured(),
                container ? blankToNull(env.apply("MYSURICATES_SWITCH_SOURCE")) : null,
                container ? blankToNull(env.apply("MYSURICATES_SWITCH_PREFIX")) : null,
                providers);
    }

    /** Where each CLI looks for its login — the provider directory the generator set, else its default. */
    private Boolean loginPresent(String id) {
        return switch (id) {
            case "claude" -> !isBlank(env.apply("CLAUDE_CODE_OAUTH_TOKEN"))
                    || Files.isRegularFile(dir("CLAUDE_CONFIG_DIR", ".claude").resolve(".credentials.json"));
            case "codex" -> Files.isRegularFile(dir("CODEX_HOME", ".codex").resolve("auth.json"));
            default -> Files.isRegularFile(dir("PI_CODING_AGENT_DIR", ".pi/agent").resolve("auth.json"));
        };
    }

    private Path dir(String variable, String underHome) {
        String value = env.apply(variable);
        return isBlank(value) ? userHome.resolve(underHome) : Path.of(value);
    }

    private static Set<String> selection(String value) {
        if (isBlank(value)) {
            return Set.copyOf(PROVIDERS);
        }
        return Arrays.stream(value.split("[,\\s]+")).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
