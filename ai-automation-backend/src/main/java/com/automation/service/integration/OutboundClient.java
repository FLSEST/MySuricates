package com.automation.service.integration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One outbound HTTP client of an integration, handed out by {@link OutboundHttp} for a declared
 * {@link OutboundTarget}. What every client does without the caller having to think of it:
 *
 * <ul>
 *   <li><b>Never follows a redirect itself</b> ({@link HttpClient.Redirect#NEVER}). A caller that
 *       wants to follow one sends the next hop through {@link #send} again — so every hop passes the
 *       same check.</li>
 *   <li><b>Checks the target</b> of every request with {@link OutboundTargetGuard} when the target
 *       kind is guarded and its hardening flag is on; a refused target throws
 *       {@link IllegalArgumentException} before any connection is made.</li>
 *   <li><b>Connects to the checked address</b> for plain {@code http://} URLs with a host name: the
 *       connection goes to the address the check accepted (routed as an HTTP proxy hop to that
 *       address, so the request still carries the original {@code Host}), and the check runs again
 *       at that moment. A DNS answer that changes between check and connect (DNS rebinding) cannot
 *       redirect the call into the own network. For {@code https://} the HTTP client resolves the
 *       name itself; a rebound name then fails the TLS certificate check, because the internal
 *       service cannot present a certificate for the public name. A proxy configured for the JVM
 *       ({@code http.proxyHost}) wins over the pinning — the proxy resolves the name then.</li>
 * </ul>
 */
public class OutboundClient {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final OutboundTarget target;
    private final OutboundTargetGuard guard;
    private final HttpClient http;

    OutboundClient(OutboundTarget target, OutboundTargetGuard guard, Duration connectTimeout) {
        this.target = Objects.requireNonNull(target, "target");
        if (target.guarded()) Objects.requireNonNull(guard, "a guarded target needs the guard");
        this.guard = guard;
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER);
        if (connectTimeout != null) builder.connectTimeout(connectTimeout);
        if (target.guarded()) builder.proxy(new CheckedAddressSelector());
        this.http = builder.build();
    }

    public OutboundTarget target() {
        return target;
    }

    /**
     * The target check of this client's {@link OutboundTarget} on its own — for a caller that wants
     * to log or report a refusal before it builds the request. {@link #send} runs it anyway.
     */
    public void checkTarget(URI uri) {
        if (target.guarded()) guard.check(uri, target.flag());
    }

    /**
     * Sends {@code request} like {@link HttpClient#send} — after the target check of this client's
     * {@link OutboundTarget}. A 3xx comes back as it is.
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        checkTarget(request.uri());
        return http.send(request, handler);
    }

    /** IPv4 dotted quad or IPv6 (bracketed or not) — a host that needs no DNS lookup. */
    public static boolean isIpLiteral(String host) {
        return host != null && (host.startsWith("[") || host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches());
    }

    /**
     * Runs the check again when the HTTP client opens the connection and, for plain http to a host
     * name, routes the request to the address that passed (see class comment).
     */
    private final class CheckedAddressSelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            List<Proxy> configured = configured(uri);
            InetAddress checked = guard.checkedAddress(uri, target.flag());
            boolean direct = configured.isEmpty() || configured.get(0).type() == Proxy.Type.DIRECT;
            if (checked == null || !direct || isIpLiteral(uri.getHost())
                    || !"http".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))) {
                return configured;
            }
            try {
                InetAddress pinned = InetAddress.getByAddress(uri.getHost(), checked.getAddress());
                int port = uri.getPort() == -1 ? 80 : uri.getPort();
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pinned, port)));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Checked address of " + uri.getHost() + " is invalid", e);
            }
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            // nothing to remember: the next request checks and resolves afresh
        }

        /** What the client would use without this selector: the JVM's default proxy selector. */
        private List<Proxy> configured(URI uri) {
            ProxySelector system = ProxySelector.getDefault();
            List<Proxy> proxies = system == null ? null : system.select(uri);
            return proxies == null || proxies.isEmpty() ? List.of(Proxy.NO_PROXY) : proxies;
        }
    }
}
