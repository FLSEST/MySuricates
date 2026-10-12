package com.automation.service.project;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * Whether this instance can start containers, and if not, what would change that (TODO 0ad8853b).
 *
 * <p>{@link DockerExpectation} answers whether Docker responds. That alone told an instance in a
 * container nothing useful: there the socket is left out on purpose (secure by default) and "Docker
 * is not set up on this machine" was simply wrong. The starter scripts leave the runtime and the
 * name of the switch in the environment ({@code MYSURICATES_RUNTIME}, {@code MYSURICATES_SWITCH_PREFIX},
 * {@code MYSURICATES_SWITCH_SOURCE}, see scripts/lib/host-mounts.sh), so the hint can name the exact
 * switch, e.g. {@code TEST_DOCKER_SOCKET=1} in {@code ~/.config/mysuricates/staging.env}. Tryout and
 * deploy/ do not set {@code MYSURICATES_RUNTIME}; there the marker file the container runtime creates
 * ({@code /.dockerenv}, {@code /run/.containerenv}) tells that the instance runs in a container.
 *
 * <p>The texts end up in step outputs, analyzer findings and the container page; they name switches and
 * a config file path, never a secret.
 */
@Component
public class DockerAccess {

    /**
     * @param state        what {@link DockerExpectation#probe()} found
     * @param inContainer  the instance itself runs in a container
     * @param socketSwitch name of the switch that mounts the socket ({@code TEST_DOCKER_SOCKET}), or null
     * @param switchSource where that switch is changed (a file, or how the starter is called), or null
     */
    public record Status(DockerExpectation.State state, boolean inContainer, String socketSwitch, String switchSource) {

        public boolean reachable() {
            return state == DockerExpectation.State.UP;
        }

        /** Why containers cannot start here and what to do, in German; empty when Docker responds. */
        public String hint() {
            if (reachable()) return "";
            if (state == DockerExpectation.State.DOWN) {
                return "Docker ist eingerichtet, antwortet aber nicht – den Docker-Dienst starten.";
            }
            if (!inContainer) {
                return "Docker ist auf diesem Rechner nicht eingerichtet – Docker installieren und starten.";
            }
            String base = "Die Instanz läuft im Container ohne Docker-Socket (sicherer Standard).";
            if (socketSwitch == null) {
                return base + " Sie muss mit eingebundenem Socket neu gestartet werden; der Socket gibt ihr"
                        + " Root-gleiche Rechte auf dem Rechner.";
            }
            return base + " Einschalten: " + socketSwitch + "=1" + where(" in ", switchSource)
                    + ", dann neu starten. Der Socket gibt der Instanz Root-gleiche Rechte auf dem Rechner.";
        }

        /** The same hint in English; empty when Docker responds. */
        public String hintEn() {
            if (reachable()) return "";
            if (state == DockerExpectation.State.DOWN) {
                return "Docker is set up but does not respond - start the Docker daemon.";
            }
            if (!inContainer) {
                return "Docker is not set up on this machine - install and start Docker.";
            }
            String base = "The instance runs in a container without the Docker socket (secure default).";
            if (socketSwitch == null) {
                return base + " It has to be restarted with the socket mounted; the socket gives it"
                        + " root-equivalent access to the host.";
            }
            return base + " To switch it on: " + socketSwitch + "=1" + where(" in ", switchSource)
                    + ", then restart. The socket gives the instance root-equivalent access to the host.";
        }

        private static String where(String preposition, String source) {
            return source == null ? "" : preposition + source;
        }
    }

    private final DockerExpectation docker;
    private final UnaryOperator<String> env;
    private final BooleanSupplier containerMarker;

    @Autowired
    public DockerAccess(DockerExpectation docker) {
        this(docker, System::getenv,
                () -> Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")));
    }

    DockerAccess(DockerExpectation docker, UnaryOperator<String> env, BooleanSupplier containerMarker) {
        this.docker = docker;
        this.env = env;
        this.containerMarker = containerMarker;
    }

    /** Probes Docker once (one ping, or a file check when no socket is configured). */
    public Status status() {
        String runtime = env.apply("MYSURICATES_RUNTIME");
        boolean inContainer = runtime == null || runtime.isBlank()
                ? containerMarker.getAsBoolean()
                : "container".equals(runtime);
        String prefix = inContainer ? blankToNull(env.apply("MYSURICATES_SWITCH_PREFIX")) : null;
        return new Status(docker.probe(), inContainer,
                prefix == null ? null : prefix + "DOCKER_SOCKET",
                inContainer ? blankToNull(env.apply("MYSURICATES_SWITCH_SOURCE")) : null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
