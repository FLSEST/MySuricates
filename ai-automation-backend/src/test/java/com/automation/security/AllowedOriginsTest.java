package com.automation.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedOriginsTest {

    /** What OwnTailnetAddresses would report for this instance. */
    private static final Set<String> OWN = Set.of("100.64.0.7", "mine.tail1234.ts.net");

    private final AtomicInteger tailnetLookups = new AtomicInteger();

    private AllowedOrigins policy(String configured, Set<String> ownTailnetHosts) {
        return new AllowedOrigins(configured, () -> {
            tailnetLookups.incrementAndGet();
            return ownTailnetHosts;
        });
    }

    private AllowedOrigins defaults() {
        return policy(AllowedOrigins.DEFAULT_ORIGINS, OWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:5173",
            "https://localhost:3000", "https://localhost:5173"})
    void localhostDevServers_areAllowed_withoutAskingTailscale(String origin) {
        assertThat(defaults().checkOrigin(origin)).isEqualTo(origin);
        assertThat(tailnetLookups).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://100.64.0.7:3000", "https://100.64.0.7:3000", "https://100.64.0.7",
            "https://mine.tail1234.ts.net", "https://mine.tail1234.ts.net:3000",
            "http://mine.tail1234.ts.net:5173", "https://MINE.tail1234.ts.net:3000"})
    void ownTailscaleAddresses_areAllowed_onAnyPort(String origin) {
        assertThat(defaults().checkOrigin(origin)).isEqualTo(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.tail0000.ts.net:3000",            // foreign tailnet (matched *.tail*.ts.net:*)
            "https://peer.tail1234.ts.net:3000",            // foreign host in our own tailnet
            "http://100.64.0.8:3000",                       // foreign Tailscale IP (matched 100.*:3000)
            "http://100.evil.example:3000",                 // matched http://100.*:3000
            "https://mine.tail1234.ts.net.evil.example",    // own name as prefix
            "https://evilmine.tail1234.ts.net",
            "https://mine.tail1234.ts.net@evil.example",    // userinfo: the host is evil.example
            "https://100.64.0.7:3000/path", "https://100.64.0.7:3000?x=1", "https://100.64.0.7:3000#x",
            "ws://100.64.0.7:3000", "null", "*", "", "http://localhost:8080"})
    void foreignOrLookalikeOrigins_areRejected(String origin) {
        assertThat(defaults().checkOrigin(origin)).isNull();
        assertThat(defaults().isAllowed(origin)).isFalse();
    }

    @Test
    void missingOrigin_isRejected() {
        assertThat(defaults().checkOrigin(null)).isNull();
    }

    @Test
    void withoutOwnTailnetAddresses_onlyTheConfiguredListCounts() {
        AllowedOrigins policy = policy(AllowedOrigins.DEFAULT_ORIGINS, Set.of());

        assertThat(policy.isAllowed("https://100.64.0.7:3000")).isFalse();
        assertThat(policy.isAllowed("https://mine.tail1234.ts.net")).isFalse();
        assertThat(policy.isAllowed("http://localhost:3000")).isTrue();
    }

    @Test
    void defaultList_isLocalhostOnly() {
        assertThat(defaults().configuredPatterns()).containsExactly(
                "http://localhost:3000", "http://localhost:5173", "https://localhost:3000", "https://localhost:5173");
    }

    @Test
    void configuredOrigins_areTrimmed_andReplaceTheDefaultAsBefore() {
        AllowedOrigins policy = policy(" https://proxy.example.com , ,http://127.0.0.1:9123 ", Set.of());

        assertThat(policy.configuredPatterns()).containsExactly("https://proxy.example.com", "http://127.0.0.1:9123");
        assertThat(policy.isAllowed("https://proxy.example.com")).isTrue();
        assertThat(policy.isAllowed("http://127.0.0.1:9123")).isTrue();
        assertThat(policy.isAllowed("http://localhost:3000")).isFalse();
    }

    @Test
    void corsConfiguration_grantsNoCredentials_andAsksThePolicy() {
        CorsConfiguration cors = defaults().corsConfiguration();

        assertThat(cors.getAllowCredentials()).isNull();
        assertThat(cors.checkOrigin("https://mine.tail1234.ts.net:3000")).isEqualTo("https://mine.tail1234.ts.net:3000");
        assertThat(cors.checkOrigin("https://evil.tail0000.ts.net:3000")).isNull();
        assertThat(cors.getAllowedOriginPatterns()).isEqualTo(defaults().configuredPatterns());
        assertThat(cors.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders()).containsExactly("*");
    }
}
