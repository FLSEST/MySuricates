package com.automation.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run token of the run on this thread (TODO cfb5d56b): a short-lived, read-only credential for the
 * allowlisted GET endpoints, issued by {@code RunTokenService} only to runs whose workflow asks for it.
 *
 * <p>Bound to the run's thread like {@code LiveOutput} — a plain, non-inheritable {@link ThreadLocal}: a
 * sub-workflow runs on a thread of its own and gets its own token or none. Never a run variable, because
 * variables are copied into the persisted input of child runs. {@link ChildProcessEnv#isolate} puts the
 * bound token into the environment of every AI CLI and shell process the thread starts.
 *
 * <p>Also the one place that knows every live token value, so step outputs, live log lines and
 * broadcasts can be {@linkplain #mask masked} before they are stored or sent.
 */
public final class RunAccess {

    /** Environment variable carrying the token. */
    public static final String TOKEN_ENV = "MYSURICATES_RUN_TOKEN";
    /** Environment variable carrying the API base, ending in {@code /api/v1}. */
    public static final String API_ENV = "MYSURICATES_API";
    /** What a token value is replaced with in outputs. */
    public static final String MASK = "***";

    /** Hosts a token must reach directly: an inherited proxy setting would otherwise send it out. */
    private static final String LOCAL_HOSTS = "localhost,127.0.0.1,::1";
    private static final Set<String> WILDCARD_ADDRESSES = Set.of("0.0.0.0", "::", "[::]");

    /** The token of one run and the API base it is valid for. */
    public record Grant(String token, String apiBase) {
        @Override
        public String toString() {
            return "Grant[apiBase=" + apiBase + "]";   // never the token itself
        }
    }

    private static final ThreadLocal<Grant> CURRENT = new ThreadLocal<>();
    private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();

    private RunAccess() {
    }

    /** Does a workflow ask for a run token? {@code metadata.permissions} is {@code ["read"]} or {@code "read"}. */
    public static boolean requested(Map<String, Object> workflowMetadata) {
        if (workflowMetadata == null) {
            return false;
        }
        Object permissions = workflowMetadata.get("permissions");
        if (permissions instanceof java.util.Collection<?> list) {
            return list.stream().anyMatch(p -> "read".equals(String.valueOf(p)));
        }
        return "read".equals(String.valueOf(permissions));
    }

    /**
     * The API base a run's own processes reach this backend at: the loopback interface (or the one address
     * {@code server.address} binds to) and the port the backend listens on. Never the instance URL written
     * for agents elsewhere ({@code InstanceAddress}): in a container that is the published port, and it may
     * name another host — a token sent there would leave the machine.
     */
    public static String localApiBase(String boundAddress, String port) {
        String address = boundAddress == null ? "" : boundAddress.strip();
        String host = address.isEmpty() || WILDCARD_ADDRESSES.contains(address) ? "127.0.0.1" : address;
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + port + "/api/v1";
    }

    /** Binds the run's token to the calling thread until {@link #clear()}. */
    public static void bind(Grant grant) {
        CURRENT.set(grant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Grant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Puts the bound token and API base into a child's environment; no-op on a thread without a token. */
    public static void addTo(Map<String, String> environment) {
        Grant grant = CURRENT.get();
        if (grant == null) {
            return;
        }
        environment.put(TOKEN_ENV, grant.token());
        environment.put(API_ENV, grant.apiBase());
        String direct = directHosts(grant.apiBase());
        for (String name : List.of("no_proxy", "NO_PROXY")) {
            String existing = environment.get(name);
            environment.put(name, existing == null || existing.isBlank() ? direct : existing + "," + direct);
        }
    }

    /** The local hosts plus the API's own host, should the backend be bound to another address. */
    private static String directHosts(String apiBase) {
        String host = java.net.URI.create(apiBase).getHost();
        if (host == null) {
            return LOCAL_HOSTS;
        }
        host = host.replace("[", "").replace("]", "");
        return List.of(LOCAL_HOSTS.split(",")).contains(host) ? LOCAL_HOSTS : LOCAL_HOSTS + "," + host;
    }

    /** Called when a token is issued: from now on it is masked everywhere. */
    public static void register(String token) {
        LIVE.add(token);
    }

    /** Called when a token is revoked. */
    public static void unregister(String token) {
        if (token != null) {
            LIVE.remove(token);
        }
    }

    /** A copy of a broadcast payload with its string values {@linkplain #mask masked}. */
    public static Map<String, Object> maskValues(Map<String, Object> payload) {
        if (payload == null || LIVE.isEmpty()) {
            return payload;
        }
        Map<String, Object> masked = new java.util.LinkedHashMap<>(payload);
        masked.replaceAll((key, value) -> value instanceof String text ? mask(text) : value);
        return masked;
    }

    /** {@code text} with every live token value replaced by {@link #MASK}. */
    public static String mask(String text) {
        if (text == null || LIVE.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String token : LIVE) {
            if (masked.contains(token)) {
                masked = masked.replace(token, MASK);
            }
        }
        return masked;
    }
}
