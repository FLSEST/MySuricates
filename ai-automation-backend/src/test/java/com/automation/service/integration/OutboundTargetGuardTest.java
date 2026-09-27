package com.automation.service.integration;

import com.automation.testsupport.TestTargetGuards;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundTargetGuardTest {

    /** Fixed DNS table; unknown names fail like a real lookup. */
    private static OutboundTargetGuard guard(boolean on, Map<String, String[]> dns) {
        return new OutboundTargetGuard(TestTargetGuards.registry(on), host -> {
            String[] ips = dns.get(host);
            if (ips == null) return new InetAddress[]{InetAddress.getByName(host)}; // literals only, no DNS
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) out[i] = InetAddress.getByName(ips[i]);
            return out;
        });
    }

    private static final OutboundTargetGuard LITERALS = guard(true, Map.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "0.1.2.3",
            "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.10",
            "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.254",
            "224.0.0.1", "239.255.255.250", "255.255.255.255",
            "[::1]", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[febf::1]", "[fec0::1]",
            "[ff02::1]", "[::ffff:127.0.0.1]", "[::ffff:169.254.169.254]", "[::ffff:10.0.0.1]",
            "[::127.0.0.1]", "[64:ff9b::a9fe:a9fe]"})
    void refusesInternalAddresses(String host) {
        assertThatThrownBy(() -> LITERALS.check(URI.create("http://" + host + "/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local or private network address")
                .hasMessageContaining("automation.hardening.private-network-guard=false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "172.32.0.1", "172.15.255.255", "100.128.0.1",
            "100.63.255.255", "192.169.0.1", "169.255.0.1", "203.0.113.10",
            "[2606:4700::1111]", "[2001:4860:4860::8888]", "[::ffff:8.8.8.8]", "[64:ff9b::808:808]"})
    void allowsPublicAddresses(String host) {
        assertThatCode(() -> LITERALS.check(URI.create("https://" + host + "/feed.ics"))).doesNotThrowAnyException();
    }

    @Test
    void checksTheResolvedAddressesNotTheName() {
        OutboundTargetGuard guard = guard(true, Map.of(
                "rebind.example", new String[]{"127.0.0.1"},
                "metadata.example", new String[]{"169.254.169.254"},
                "lan.example", new String[]{"fd00::5"},
                "mixed.example", new String[]{"93.184.216.34", "192.168.0.5"},
                "public.example", new String[]{"93.184.216.34", "2606:4700::1111"}));

        assertThatThrownBy(() -> guard.check(URI.create("https://rebind.example/cal.ics")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rebind.example (127.0.0.1)");
        assertThatThrownBy(() -> guard.check(URI.create("http://metadata.example/latest/meta-data")))
                .hasMessageContaining("169.254.169.254");
        assertThatThrownBy(() -> guard.check(URI.create("http://lan.example/"))).isInstanceOf(IllegalArgumentException.class);
        // one internal address among several is enough — the client may pick any of them
        assertThatThrownBy(() -> guard.check(URI.create("http://mixed.example/"))).hasMessageContaining("192.168.0.5");
        assertThatCode(() -> guard.check(URI.create("https://public.example/feed"))).doesNotThrowAnyException();
    }

    @Test
    void localhostByNameIsRefused() {
        assertThatThrownBy(() -> TestTargetGuards.on().check(URI.create("http://localhost:9090/api")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void unresolvableHostIsAnErrorNotAPass() {
        OutboundTargetGuard guard = new OutboundTargetGuard(TestTargetGuards.registry(true), host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> guard.check(URI.create("https://nowhere.invalid/x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Host nowhere.invalid could not be resolved");
        OutboundTargetGuard empty = new OutboundTargetGuard(TestTargetGuards.registry(true), host -> new InetAddress[0]);
        assertThatThrownBy(() -> empty.check(URI.create("https://nowhere.invalid/x"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void switchedOff_allowsLocalTargetsWithoutEvenResolving() {
        AtomicInteger lookups = new AtomicInteger();
        OutboundTargetGuard off = new OutboundTargetGuard(TestTargetGuards.registry(false), host -> {
            lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        });

        assertThat(off.enabled()).isFalse();
        assertThatCode(() -> off.check(URI.create("http://127.0.0.1:8080/x"))).doesNotThrowAnyException();
        assertThatCode(() -> off.check(URI.create("http://169.254.169.254/"))).doesNotThrowAnyException();
        assertThat(lookups).hasValue(0);
    }

    @Test
    void isOnByDefault() {
        assertThat(new OutboundTargetGuard(com.automation.modules.ModuleRegistry.of(Map.of(), Map.of())).enabled()).isTrue();
    }

    @Test
    void urlWithoutHostIsRefused() {
        assertThatThrownBy(() -> LITERALS.check(URI.create("file:///etc/passwd"))).isInstanceOf(IllegalArgumentException.class);
    }
}
