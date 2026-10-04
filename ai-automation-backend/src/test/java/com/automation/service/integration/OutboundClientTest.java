package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;
import com.automation.testsupport.TestTargetGuards;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer server;
    private String base;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> hostHeader = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            hits.incrementAndGet();
            hostHeader.set(ex.getRequestHeaders().getFirst("Host"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/moved", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static HttpResponse<String> get(OutboundClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aFixedHostIsNotChecked() throws Exception {
        OutboundClient client = OutboundHttp.fixedHost(TIMEOUT);

        assertThat(client.target().kind()).isEqualTo(OutboundTarget.Kind.FIXED_HOST);
        assertThat(get(client, base + "/ok").body()).isEqualTo("ok");
    }

    @Test
    void noClientFollowsARedirectByItself_theHopComesBackToTheCaller() throws Exception {
        OutboundClient fixed = OutboundHttp.fixedHost(TIMEOUT);
        OutboundClient own = TestTargetGuards.outbound().client(OutboundTarget.ownService(), TIMEOUT);

        HttpResponse<String> response = get(fixed, base + "/moved");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("http://169.254.169.254/latest/meta-data/");
        assertThat(get(own, base + "/moved").statusCode()).isEqualTo(302);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void aPublicSourceIsRefusedBeforeAnyRequestByDefault() {
        OutboundClient client = TestTargetGuards.outbound().client(OutboundTarget.publicSource(), TIMEOUT);

        assertThatThrownBy(() -> get(client, base + "/ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local or private network address")
                .hasMessageContaining("automation.hardening.private-network-guard=false");
        assertThat(hits.get()).isZero();
    }

    @Test
    void anOwnServiceIsReachableByDefault_andRefusedWithItsSwitchOn() throws Exception {
        OutboundClient open = TestTargetGuards.outbound().client(OutboundTarget.ownService(), TIMEOUT);
        assertThat(get(open, base + "/ok").body()).isEqualTo("ok");

        OutboundClient guarded = TestTargetGuards.outbound(TestTargetGuards.on()).client(OutboundTarget.ownService(), TIMEOUT);
        assertThatThrownBy(() -> get(guarded, base + "/ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("automation.hardening.private-network-guard-own-services=false");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void anOwnServiceWithItsOwnSwitchNamesThatSwitch() {
        OutboundClient httpApi = TestTargetGuards.outbound(TestTargetGuards.on())
                .client(OutboundTarget.ownService(ModuleCatalog.PRIVATE_NETWORK_GUARD_HTTP_API), TIMEOUT);

        assertThatThrownBy(() -> get(httpApi, base + "/ok"))
                .hasMessageContaining("automation.hardening.private-network-guard-http-api=false");
    }

    @Test
    void plainHttpGoesToTheCheckedAddress_withTheOriginalHostHeader() throws Exception {
        // "pinned.test" never resolves in real DNS (RFC 2606): the call only arrives because the client
        // connects to the address the check accepted instead of looking the name up again.
        OutboundTargetGuard guard = new OutboundTargetGuard(TestTargetGuards.registry(true),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, address -> false);
        OutboundClient client = new OutboundHttp(guard).client(OutboundTarget.publicSource(), TIMEOUT);

        assertThat(get(client, "http://pinned.test:" + port + "/ok").body()).isEqualTo("ok");
        assertThat(hostHeader.get()).isEqualTo("pinned.test:" + port);
    }

    @Test
    void aNameThatRebindsBetweenCheckAndConnectIsRefused() {
        AtomicInteger lookups = new AtomicInteger();
        OutboundTargetGuard guard = new OutboundTargetGuard(TestTargetGuards.registry(true), host ->
                new InetAddress[]{InetAddress.getByName(lookups.incrementAndGet() == 1 ? "203.0.113.10" : "127.0.0.1")});
        OutboundClient client = new OutboundHttp(guard).client(OutboundTarget.publicSource(), TIMEOUT);

        assertThatThrownBy(() -> get(client, "http://rebind.test:" + port + "/ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rebind.test (127.0.0.1)");
        assertThat(lookups.get()).isEqualTo(2);
        assertThat(hits.get()).isZero();
    }

    @Test
    void withTheSwitchOff_nothingIsResolvedOrPinned() throws Exception {
        OutboundTargetGuard guard = new OutboundTargetGuard(TestTargetGuards.registry(false), host -> {
            throw new AssertionError("no lookup while the switch is off");
        });
        OutboundClient client = new OutboundHttp(guard).client(OutboundTarget.publicSource(), TIMEOUT);

        assertThat(get(client, base + "/ok").body()).isEqualTo("ok");
    }

    @Test
    void loopbackOnly_reachesTheLoopbackServer_andRefusesEverythingElseWithoutALookup() throws Exception {
        OutboundHttp offline = TestTargetGuards.loopbackOnly();
        for (OutboundTarget target : new OutboundTarget[]{OutboundTarget.publicSource(), OutboundTarget.ownService(),
                OutboundTarget.ownService(ModuleCatalog.PRIVATE_NETWORK_GUARD_HTTP_API)}) {
            OutboundClient client = offline.client(target, TIMEOUT);
            assertThat(get(client, base + "/ok").body()).isEqualTo("ok");
            assertThatThrownBy(() -> get(client, "https://hooks.slack.com/test"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be resolved");
            assertThatThrownBy(() -> get(client, "http://203.0.113.10/x")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(hits.get()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "203.0.113.10", "[::1]", "::1"})
    void ipLiteralsNeedNoPinning(String host) {
        assertThat(OutboundClient.isIpLiteral(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org", "pinned.test", "localhost", "1.2.3.example"})
    void namesArePinned(String host) {
        assertThat(OutboundClient.isIpLiteral(host)).isFalse();
    }

    @Test
    void aFixedHostCarriesNoFlag_everyOtherKindDoes() {
        assertThat(OutboundTarget.fixedHost().guarded()).isFalse();
        assertThat(OutboundTarget.publicSource().flag()).isEqualTo(ModuleCatalog.PRIVATE_NETWORK_GUARD);
        assertThat(OutboundTarget.ownService().flag()).isEqualTo(ModuleCatalog.PRIVATE_NETWORK_GUARD_OWN_SERVICES);
        assertThatThrownBy(() -> new OutboundTarget(OutboundTarget.Kind.FIXED_HOST, ModuleCatalog.PRIVATE_NETWORK_GUARD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundTarget(OutboundTarget.Kind.OWN_SERVICE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
