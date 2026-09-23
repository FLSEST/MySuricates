package com.automation.security;

import com.automation.service.system.OwnTailnetAddresses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which browser origins may talk to this instance across origins — one answer for the CORS filter,
 * both WebSocket handshakes and the OAuth popup's {@code postMessage} target (a8056256):
 * <ul>
 *   <li>the configured list {@code automation.cors.allowed-origins} ({@code AUTOMATION_CORS_ORIGINS}),
 *       by default {@link #DEFAULT_ORIGINS}; entries are origin patterns as in
 *       {@link CorsConfiguration#setAllowedOriginPatterns};</li>
 *   <li>while the module {@code remote-access} is on, this instance's own Tailscale IPv4 and
 *       MagicDNS name — http or https, any port ({@link OwnTailnetAddresses}).</li>
 * </ul>
 * The former default patterns {@code http://100.*:3000} and {@code https://*.tail*.ts.net:*} also
 * matched {@code http://100.evil.example:3000} and every other tailnet; a foreign host stays out now.
 *
 * <p>An installed instance serves its UI itself: same origin, no CORS involved. The list matters for
 * the Vite dev server (its proxy rewrites Host, not Origin) and reverse proxies that rewrite Host.
 */
@Component
public class AllowedOrigins {

    /** Default of {@code automation.cors.allowed-origins}: the dev servers on this machine. */
    public static final String DEFAULT_ORIGINS =
            "http://localhost:3000,http://localhost:5173,https://localhost:3000,https://localhost:5173";

    private final CorsConfiguration configured = new CorsConfiguration();
    private final Supplier<Set<String>> ownTailnetHosts;

    @Autowired
    public AllowedOrigins(@Value("${automation.cors.allowed-origins:" + DEFAULT_ORIGINS + "}") String configuredOrigins,
                          OwnTailnetAddresses ownTailnet) {
        this(configuredOrigins, ownTailnet::hosts);
    }

    AllowedOrigins(String configuredOrigins, Supplier<Set<String>> ownTailnetHosts) {
        this.configured.setAllowedOriginPatterns(Arrays.stream(configuredOrigins.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList());
        this.ownTailnetHosts = ownTailnetHosts;
    }

    /**
     * {@code origin} if it may call in, else {@code null} — the contract of
     * {@link CorsConfiguration#checkOrigin}. The configured list answers first, so a localhost
     * request never starts a Tailscale query.
     */
    @Nullable
    public String checkOrigin(@Nullable String origin) {
        String allowed = configured.checkOrigin(origin);
        if (allowed != null) {
            return allowed;
        }
        String host = webOriginHost(origin);
        return host != null && ownTailnetHosts.get().contains(host) ? origin : null;
    }

    public boolean isAllowed(@Nullable String origin) {
        return checkOrigin(origin) != null;
    }

    /** The configured entries as the CORS filter documents them. */
    public List<String> configuredPatterns() {
        return configured.getAllowedOriginPatterns();
    }

    /**
     * CORS rules for the global filter: this origin check, the API's methods, any request header —
     * and no credentials. Authentication is the {@code X-API-Key} header; nothing uses cookies.
     */
    public CorsConfiguration corsConfiguration() {
        CorsConfiguration cors = new PolicyCorsConfiguration(this);
        cors.setAllowedOriginPatterns(configuredPatterns());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        return cors;
    }

    /** Lower-case host of a bare {@code http(s)://host[:port]} origin; {@code null} for anything else. */
    @Nullable
    static String webOriginHost(@Nullable String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(origin.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        String path = uri.getRawPath();
        boolean bare = uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (path == null || path.isEmpty() || "/".equals(path));
        return web && bare && uri.getHost() != null ? OwnTailnetAddresses.normalizeHost(uri.getHost()) : null;
    }

    /** Spring asks the configuration for the origin; the answer comes from the policy, not from patterns alone. */
    private static final class PolicyCorsConfiguration extends CorsConfiguration {

        private final AllowedOrigins policy;

        PolicyCorsConfiguration(AllowedOrigins policy) {
            this.policy = policy;
        }

        @Override
        @Nullable
        public String checkOrigin(@Nullable String origin) {
            return policy.checkOrigin(origin);
        }
    }
}
