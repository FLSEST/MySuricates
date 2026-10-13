package com.automation.service.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * Where this instance can be reached from (TODO 1af11dcc): the address and port the backend listens on,
 * and whether that is this machine only or the network. Secure by default means 127.0.0.1 everywhere;
 * the settings page shows it so nobody has to guess whether {@code localhost} or a LAN address works.
 *
 * <p>Directly on the machine the backend knows its own bind ({@code server.address}, empty = every
 * interface) and port. In a container it listens on 0.0.0.0:9090 inside, which says nothing: what
 * counts is where Docker publishes the port on the host. The starter scripts pass that in
 * ({@code MYSURICATES_PUBLISHED_BIND}, {@code MYSURICATES_PUBLISHED_PORT}, see scripts/lib/host-mounts.sh
 * and scripts/tryout.sh); a container started by an older script lacks them and is reported as unknown
 * instead of guessed. {@code MYSURICATES_BIND_SWITCH} names the switch that changes the bind and
 * {@code MYSURICATES_SWITCH_SOURCE} where it is set — both are only shown, never applied here.
 *
 * <p>The answer names addresses of this machine, so it is served only with the API key.
 */
@Component
public class ListenAddresses {

    /** Reach of an address: only this machine, the network, or not known. */
    public enum Scope { LOCAL, NETWORK, UNKNOWN }

    /**
     * @param scheme    http or https
     * @param address   the bound or published address; null = every interface (or unknown, see scope)
     * @param port      the port; null when unknown
     * @param scope     who can reach it
     * @param published the address is the one Docker publishes on the host, not the backend's own bind
     */
    public record Endpoint(String scheme, String address, Integer port, Scope scope, boolean published) {}

    /**
     * @param runtime        host, container or tryout
     * @param backend        where the backend (and the API) can be reached
     * @param servesFrontend the backend delivers the UI itself (same address); otherwise e.g. Vite does
     * @param bindSwitch     the switch that changes the bind, e.g. TEST_BIND; null when there is none
     * @param switchSource   where that switch is set (a file or the starter script); null when unknown
     */
    public record Report(String runtime, Endpoint backend, boolean servesFrontend, String bindSwitch,
                         String switchSource) {}

    private static final Set<String> ALL_INTERFACES = Set.of("", "0.0.0.0", "::", "[::]", "*");

    private final Environment environment;
    private final UnaryOperator<String> env;
    private final BooleanSupplier containerMarker;

    @Autowired
    public ListenAddresses(Environment environment) {
        this(environment, System::getenv,
                () -> Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")));
    }

    ListenAddresses(Environment environment, UnaryOperator<String> env, BooleanSupplier containerMarker) {
        this.environment = environment;
        this.env = env;
        this.containerMarker = containerMarker;
    }

    public Report report() {
        String runtime = runtime();
        String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
        Endpoint backend = "host".equals(runtime) ? ownBind(scheme) : published(scheme);
        return new Report(runtime, backend,
                environment.getProperty("automation.serve-frontend", Boolean.class, false),
                blankToNull(env.apply("MYSURICATES_BIND_SWITCH")),
                blankToNull(env.apply("MYSURICATES_SWITCH_SOURCE")));
    }

    /** The reach of one address: loopback is this machine, every interface or any other address the network. */
    public static Scope scopeOf(String address) {
        String a = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        if (ALL_INTERFACES.contains(a)) return Scope.NETWORK;
        if (a.equals("localhost") || a.equals("::1") || a.startsWith("127.") || a.equals("::ffff:127.0.0.1")) {
            return Scope.LOCAL;
        }
        return Scope.NETWORK;
    }

    private String runtime() {
        String runtime = blankToNull(env.apply("MYSURICATES_RUNTIME"));
        if ("tryout".equals(runtime) || "container".equals(runtime) || "host".equals(runtime)) return runtime;
        return containerMarker.getAsBoolean() ? "container" : "host";
    }

    private Endpoint ownBind(String scheme) {
        String bind = environment.getProperty("server.address", "").strip();
        Integer port = environment.getProperty("local.server.port", Integer.class,
                environment.getProperty("server.port", Integer.class, 9090));
        return new Endpoint(scheme, ALL_INTERFACES.contains(bind) ? null : bind, port, scopeOf(bind), false);
    }

    private Endpoint published(String scheme) {
        String bind = blankToNull(env.apply("MYSURICATES_PUBLISHED_BIND"));
        Integer port = parsePort(env.apply("MYSURICATES_PUBLISHED_PORT"));
        if (bind == null || port == null) {
            return new Endpoint(scheme, null, null, Scope.UNKNOWN, true);
        }
        return new Endpoint(scheme, ALL_INTERFACES.contains(bind) ? null : bind, port, scopeOf(bind), true);
    }

    private static Integer parsePort(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,5}")) return null;
        int port = Integer.parseInt(raw.strip());
        return port > 0 && port <= 65_535 ? port : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
