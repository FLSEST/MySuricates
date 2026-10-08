package com.automation.service.isolation;

import com.automation.config.DataHome;
import com.automation.config.ProcessSandboxConfiguration;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.ai.catalog.CliCommandRunner;
import com.automation.util.HostProcessUtils;
import com.automation.util.UnattendedContainer;
import com.automation.util.UnattendedContainerCommand;
import com.automation.util.UnattendedContainerCommand.Bind;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands {@link UnattendedContainer} the startup switch {@code automation.hardening.unattended-in-container}
 * and, per start, what a container gets (TODO 6ee57b87): the image, the user, the secrets to cover
 * ({@link ProcessSandboxConfiguration#hiddenPaths()}), the home and data directories that are never mounted
 * as a whole, the backend's startup configuration (read-only where a mount exposes it) and the CLI programs
 * with their logins ({@link UnattendedCliAccess}).
 *
 * <p>With the switch on it checks once at startup, in the background, that Docker and the image are there
 * and Claude has a login for containers, and removes containers an earlier backend left running. A backend
 * that runs in a container itself cannot use the mode yet: its paths are not the host paths Docker binds,
 * so every such start fails with a message saying so.
 */
@Slf4j
@Component
public class UnattendedContainerConfiguration {

    static final String DEFAULT_IMAGE = "automation-agent-workflow:latest";
    private static final int PROBE_SECONDS = 15;

    private final ModuleRegistry modules;
    private final ProcessSandboxConfiguration sandbox;
    private final UnattendedCliAccess cliAccess;
    private final HostProcessUtils processUtils;
    private final CliCommandRunner runner;
    private final Environment environment;

    public UnattendedContainerConfiguration(ModuleRegistry modules, ProcessSandboxConfiguration sandbox,
                                            UnattendedCliAccess cliAccess, HostProcessUtils processUtils,
                                            CliCommandRunner runner, Environment environment) {
        this.modules = modules;
        this.sandbox = sandbox;
        this.cliAccess = cliAccess;
        this.processUtils = processUtils;
        this.runner = runner;
        this.environment = environment;
    }

    @PostConstruct
    void configure() {
        boolean enabled = modules.isEnabled(ModuleCatalog.UNATTENDED_IN_CONTAINER);
        UnattendedContainer.configure(new UnattendedContainer.Policy(enabled, this::setup, this::remove));
        if (enabled) {
            Thread.ofVirtual().name("unattended-container-check").start(this::checkAndSweep);
        }
    }

    UnattendedContainerCommand.Setup setup() {
        if ("container".equals(System.getenv("MYSURICATES_RUNTIME"))) {
            throw new IllegalStateException("automation.hardening.unattended-in-container is not available for an "
                    + "instance that runs in a container itself yet — turn it off or run the backend on the host");
        }
        Path home = Path.of(System.getProperty("user.home"));
        int[] ids = hostIds();
        List<Bind> readOnly = new ArrayList<>();
        Path localtime = Path.of("/etc/localtime");
        if (Files.exists(localtime)) {
            readOnly.add(new Bind(localtime, localtime.toString(), true));
        }
        String tz = System.getenv("TZ");
        return new UnattendedContainerCommand.Setup(
                property("image", DEFAULT_IMAGE), ids[0], ids[1],
                sandbox.hiddenPaths(), home,
                List.of(DataHome.dir(), home.resolve(".mysuricates"), home.resolve(".autotrigger")),
                startupConfiguration(),
                property("cache-volume", "mysuricates-unattended-cache"),
                property("memory", ""),
                Integer.parseInt(property("pids-limit", "4096")),
                ProcessHandle.current().pid(),
                cliAccess.all(), readOnly,
                tz == null || tz.isBlank() ? Map.of() : Map.of("TZ", tz.strip()));
    }

    /**
     * Where this backend reads its startup configuration from: {@code config/} and {@code application.*} in
     * its working directory (the dev instance: {@code ai-automation-backend/}, inside the repository) and any
     * file location Spring was pointed at. Changed by an unattended run, it could turn this mode off for the
     * next start without git showing it.
     */
    List<Path> startupConfiguration() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        List<Path> paths = new ArrayList<>(List.of(workingDirectory.resolve("config"),
                workingDirectory.resolve("application.yml"), workingDirectory.resolve("application.properties")));
        for (String property : List.of("spring.config.location", "spring.config.additional-location")) {
            for (String location : environment.getProperty(property, "").split(",")) {
                String file = location.strip().replaceFirst("^optional:", "").replaceFirst("^file:", "");
                if (!file.isBlank() && !file.startsWith("classpath:")) {
                    paths.add(workingDirectory.resolve(file).normalize());
                }
            }
        }
        return paths;
    }

    private String property(String name, String fallback) {
        return environment.getProperty("automation.unattended-container." + name, fallback).strip();
    }

    /** uid and gid of this JVM, as files it creates get them; the container user owns what it writes alike. */
    private static int[] hostIds() {
        Path probe = null;
        try {
            probe = Files.createTempFile("mysuricates-ids-", ".probe");
            return new int[] {(Integer) Files.getAttribute(probe, "unix:uid"), (Integer) Files.getAttribute(probe, "unix:gid")};
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("Unattended container mode cannot determine this user's uid/gid", e);
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // a probe left in the temp directory is harmless
                }
            }
        }
    }

    void remove(String name) {
        try {
            runner.run(List.of("docker", "rm", "-f", name), PROBE_SECONDS);
        } catch (IOException e) {
            log.debug("Container {} already gone: {}", name, e.getMessage());
        }
    }

    private void checkAndSweep() {
        String image = property("image", DEFAULT_IMAGE);
        if (!processUtils.isOnPath("docker")) {
            log.error("Unattended container mode is on, but docker is not installed: AI CLIs and shell blocks outside "
                    + "runs started by a person will not start");
            return;
        }
        try {
            runner.run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image), PROBE_SECONDS);
            log.info("Unattended container mode on: image {}, CLIs {}", image, cliAccess.all().keySet());
        } catch (IOException e) {
            log.error("Unattended container mode is on, but the image {} is missing — build it with "
                    + "scripts/build-agent-images.sh agent-workflow; until then AI CLIs and shell blocks outside runs "
                    + "started by a person will not start ({})", image, e.getMessage());
        }
        if (cliAccess.all().containsKey("claude") && !cliAccess.claudeLoggedIn()) {
            log.warn("Claude in unattended containers is not logged in: run `claude setup-token` once and put "
                    + "CLAUDE_CODE_OAUTH_TOKEN=… into ~/.config/mysuricates/ai.env (chmod 600)");
        }
        sweepOrphans();
    }

    /** Containers whose backend is gone: a killed backend leaves its clients and their containers behind. */
    void sweepOrphans() {
        try {
            String listing = runner.run(List.of("docker", "ps", "-a", "--filter", "label=org.mysuricates.unattended=true",
                    "--format", "{{.Names}} {{.Label \"org.mysuricates.owner-pid\"}}"), PROBE_SECONDS);
            for (String line : listing.lines().toList()) {
                String[] parts = line.strip().split("\\s+");
                if (parts.length == 2 && !ownerAlive(parts[1])) {
                    log.info("Removing container {} left behind by backend process {}", parts[0], parts[1]);
                    remove(parts[0]);
                }
            }
        } catch (IOException e) {
            log.warn("Could not look for leftover unattended containers: {}", e.getMessage());
        }
    }

    private static boolean ownerAlive(String pid) {
        try {
            return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
