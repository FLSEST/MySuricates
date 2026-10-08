package com.automation.service.isolation;

import com.automation.config.HostPiConfig;
import com.automation.service.ai.AiProvider;
import com.automation.service.project.ProjectContainerClaudeAccess;
import com.automation.util.HostProcessUtils;
import com.automation.util.UnattendedContainerCommand.Bind;
import com.automation.util.UnattendedContainerCommand.CliAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * What of Claude, Codex and Pi a container of an unattended run gets (TODO 6ee57b87) — the rule of
 * scripts/lib/host-mounts.sh in its default mode {@code login}: the program read-only, and exactly one login
 * per provider. Claude gets the long-lived token ({@link ProjectContainerClaudeAccess#token()}), because its
 * credentials file is replaced on every refresh; Codex and Pi their single {@code auth.json}, read-write as
 * they refresh it in place, in a config directory of their own on the container's tmpfs. Login variables of
 * the provider ({@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}, …) go along when the backend has them.
 * Settings, hooks, plugins, memory and transcripts of the host stay out.
 */
@Component
public class UnattendedCliAccess {

    static final String CLAUDE_HOME = "/home/agent/.claude";
    static final String CODEX_HOME = "/home/agent/.codex";
    static final String PI_HOME = "/home/agent/.pi/agent";

    private final HostProcessUtils processUtils;
    private final ProjectContainerClaudeAccess claudeAccess;
    private final HostPiConfig piConfig;
    private final Map<String, List<String>> loginVariables;
    private final UnaryOperator<String> env;
    private final Path userHome;

    @Autowired
    public UnattendedCliAccess(HostProcessUtils processUtils, ProjectContainerClaudeAccess claudeAccess,
                               HostPiConfig piConfig, List<AiProvider> providers) {
        this(processUtils, claudeAccess, piConfig, loginVariables(providers), System::getenv,
                Path.of(System.getProperty("user.home")));
    }

    UnattendedCliAccess(HostProcessUtils processUtils, ProjectContainerClaudeAccess claudeAccess, HostPiConfig piConfig,
                        Map<String, List<String>> loginVariables, UnaryOperator<String> env, Path userHome) {
        this.processUtils = processUtils;
        this.claudeAccess = claudeAccess;
        this.piConfig = piConfig;
        this.loginVariables = Map.copyOf(loginVariables);
        this.env = env;
        this.userHome = userHome;
    }

    /** CLI name → what its container gets; a CLI that is not installed on the host is missing. */
    public Map<String, CliAccess> all() {
        Map<String, CliAccess> access = new LinkedHashMap<>();
        program("claude").ifPresent(p -> access.put("claude", claude(p)));
        program("codex").ifPresent(p -> access.put("codex", withLoginFile(p, "codex", codexHome().resolve("auth.json"),
                CODEX_HOME, "CODEX_HOME")));
        program("pi").ifPresent(p -> access.put("pi", withLoginFile(p, "pi", Path.of(piConfig.getConfigDir()).resolve("auth.json"),
                PI_HOME, "PI_CODING_AGENT_DIR")));
        return access;
    }

    /** Whether Claude in a container would be logged in: a token or a login variable. */
    public boolean claudeLoggedIn() {
        return claudeAccess.token().isPresent() || variables("claude").containsKey("ANTHROPIC_API_KEY");
    }

    private CliAccess claude(Path program) {
        Map<String, String> environment = variables("claude");
        environment.put("CLAUDE_CONFIG_DIR", CLAUDE_HOME);
        environment.put("DISABLE_AUTOUPDATER", "1");
        claudeAccess.token().ifPresent(token -> environment.put("CLAUDE_CODE_OAUTH_TOKEN", token));
        return new CliAccess(program, List.of(), List.of(), environment);
    }

    private CliAccess withLoginFile(Path program, String cli, Path loginFile, String home, String homeVariable) {
        Map<String, String> environment = variables(cli);
        environment.put(homeVariable, home);
        List<Bind> binds = new ArrayList<>();
        real(loginFile).ifPresent(file -> binds.add(new Bind(file, home + "/auth.json", false)));
        return new CliAccess(program, binds, List.of(home), environment);
    }

    /** The provider's login variables the backend itself has (its environment, not ai.env). */
    private Map<String, String> variables(String cli) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : loginVariables.getOrDefault(cli, List.of())) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) {
                found.put(name, value);
            }
        }
        return found;
    }

    private Path codexHome() {
        String explicit = env.apply("CODEX_HOME");
        return explicit == null || explicit.isBlank() ? userHome.resolve(".codex") : Path.of(explicit.strip());
    }

    /** The program as the host would start it, links resolved (Docker binds the target, not the link). */
    private Optional<Path> program(String cli) {
        return processUtils.findOnPath(cli).flatMap(UnattendedCliAccess::real);
    }

    private static Optional<Path> real(Path path) {
        try {
            return Files.isRegularFile(path) ? Optional.of(path.toRealPath()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Map<String, List<String>> loginVariables(List<AiProvider> providers) {
        Map<String, List<String>> variables = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            var setup = provider.setup();
            if (setup != null && setup.cli() != null) {
                variables.put(setup.cli(), setup.loginEnv());
            }
        }
        return variables;
    }
}
