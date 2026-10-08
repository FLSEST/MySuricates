package com.automation.service.project;

import com.automation.util.ClaudeOAuthToken;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.model.Bind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * What of Claude a project container gets from the host — the rule of scripts/lib/host-mounts.sh,
 * applied to the containers the platform starts per project (f1b6d06b). Claude runs there for ad-hoc
 * commands during a run ({@code claude -p} via {@code docker exec}) and in the terminal.
 *
 * <p>Until f1b6d06b every project container got {@code ~/.claude} and {@code ~/.claude.json}
 * read-write: an AI in it could read and change settings, hooks, plugins, memory and the transcripts
 * of every project on the host, and whatever it changed there ran on the host later.
 *
 * <p>The mode follows the instance ({@code MYSURICATES_AI_FROM_HOST}, set by the generator for
 * container instances; a backend on the host has none and gets the default {@code login}):
 * <ul>
 *   <li>{@code login} — the binary as a single file, read-only; the token from
 *       {@code CLAUDE_CODE_OAUTH_TOKEN} (container instance) or ~/.config/mysuricates/ai.env (host);
 *       a private config directory on the container's own tmpfs.</li>
 *   <li>{@code programs} — the same without a token: the instance shares no login.</li>
 *   <li>{@code all} — as before, only where the instance itself was given all of it.</li>
 * </ul>
 * A provider selection without claude ({@code MYSURICATES_AI_PROVIDERS}) gives the container nothing.
 */
@Component
@Slf4j
public class ProjectContainerClaudeAccess {

    static final String CONTAINER_HOME = "/home/agent";
    /** On the /tmp tmpfs of the container: private, writable for any uid, gone with the container. */
    static final String CONFIG_DIR = "/tmp/claude-config";

    /** Binds and environment for one project container. */
    public record Access(List<Bind> binds, List<String> env) {
        static final Access NONE = new Access(List.of(), List.of());
    }

    private final HostProcessUtils processUtils;
    private final UnaryOperator<String> env;
    private final Path userHome;

    @Autowired
    public ProjectContainerClaudeAccess(HostProcessUtils processUtils) {
        this(processUtils, System::getenv, Path.of(System.getProperty("user.home")));
    }

    ProjectContainerClaudeAccess(HostProcessUtils processUtils, UnaryOperator<String> env, Path userHome) {
        this.processUtils = processUtils;
        this.env = env;
        this.userHome = userHome;
    }

    /** @param hostHome the home directory on the Docker host — bind sources are host paths */
    public Access forContainer(String hostHome) {
        if (!claudeSelected()) {
            return Access.NONE;
        }
        String mode = blank(env.apply("MYSURICATES_AI_FROM_HOST")) ? "login" : env.apply("MYSURICATES_AI_FROM_HOST").trim();
        String program = hostHome + "/.local/bin/claude";
        if ("all".equals(mode)) {
            return new Access(List.of(
                    Bind.parse(program + ":" + CONTAINER_HOME + "/.local/bin/claude:ro"),
                    Bind.parse(hostHome + "/.local/share/claude:" + CONTAINER_HOME + "/.local/share/claude:ro"),
                    Bind.parse(hostHome + "/.claude:" + CONTAINER_HOME + "/.claude"),
                    Bind.parse(hostHome + "/.claude.json:" + CONTAINER_HOME + "/.claude.json")), List.of());
        }

        List<Bind> binds = new ArrayList<>();
        if (programOnHost(program)) {
            // Docker resolves the ~/.local/bin/claude link on the host: exactly the one binary, read-only
            binds.add(Bind.parse(program + ":" + CONTAINER_HOME + "/.local/bin/claude:ro"));
        }
        List<String> environment = new ArrayList<>(List.of("CLAUDE_CONFIG_DIR=" + CONFIG_DIR, "DISABLE_AUTOUPDATER=1"));
        if ("login".equals(mode)) {
            Optional<String> token = token();
            if (token.isPresent()) {
                environment.add(ClaudeOAuthToken.VARIABLE + "=" + token.get());
            } else {
                log.warn("Claude in project containers is not logged in: no plausible token — run `claude setup-token` "
                        + "once and put CLAUDE_CODE_OAUTH_TOKEN=… into ~/.config/mysuricates/ai.env (chmod 600)");
            }
        }
        return new Access(List.copyOf(binds), List.copyOf(environment));
    }

    private boolean claudeSelected() {
        String selection = env.apply("MYSURICATES_AI_PROVIDERS");
        return blank(selection) || Arrays.stream(selection.split("[,\\s]+")).anyMatch("claude"::equals);
    }

    /**
     * On the host the file is visible; a container instance only sees what the generator mounted, and it
     * mounts claude only when ~/.local/bin/claude exists on the host. A bind with a missing source would
     * make Docker create it as a root-owned directory on the host.
     */
    private boolean programOnHost(String program) {
        return "container".equals(env.apply("MYSURICATES_RUNTIME"))
                ? processUtils.isOnPath("claude")
                : Files.exists(Path.of(program));
    }

    /**
     * The instance's own token (container, login mode) first, else the file the generator reads — also the
     * login of Claude in the containers of unattended runs (TODO 6ee57b87).
     */
    public Optional<String> token() {
        String own = env.apply(ClaudeOAuthToken.VARIABLE);
        if (ClaudeOAuthToken.isPlausible(own)) {
            return Optional.of(own);
        }
        String xdg = env.apply("XDG_CONFIG_HOME");
        Path config = blank(xdg) ? userHome.resolve(".config") : Path.of(xdg);
        return ClaudeOAuthToken.fromEnvFile(config.resolve("mysuricates/ai.env")).filter(ClaudeOAuthToken::isPlausible);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
