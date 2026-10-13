package com.automation.service.system;

import com.automation.service.system.ListenAddresses.Endpoint;
import com.automation.service.system.ListenAddresses.Report;
import com.automation.service.system.ListenAddresses.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListenAddressesTest {

    private static Report report(MockEnvironment properties, Map<String, String> env, boolean marker) {
        return new ListenAddresses(properties, env::get, () -> marker).report();
    }

    @Test
    @DisplayName("on the machine the backend reports its own bind and the port it really listens on")
    void hostUsesOwnBindAndLocalPort() {
        MockEnvironment properties = new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "9090")
                .withProperty("local.server.port", "19090");

        Report report = report(properties, Map.of(
                "MYSURICATES_BIND_SWITCH", "MYSURICATES_DEV_BIND",
                "MYSURICATES_SWITCH_SOURCE", "scripts/start-mysuricates-dev.sh"), false);

        assertThat(report.runtime()).isEqualTo("host");
        assertThat(report.backend()).isEqualTo(new Endpoint("http", "127.0.0.1", 19090, Scope.LOCAL, false));
        assertThat(report.servesFrontend()).isFalse();
        assertThat(report.bindSwitch()).isEqualTo("MYSURICATES_DEV_BIND");
        assertThat(report.switchSource()).isEqualTo("scripts/start-mysuricates-dev.sh");
    }

    @Test
    @DisplayName("without server.address Tomcat listens on every interface - that is the network")
    void hostWithoutBindIsTheNetwork() {
        MockEnvironment properties = new MockEnvironment()
                .withProperty("server.ssl.enabled", "true")
                .withProperty("automation.serve-frontend", "true");

        Report report = report(properties, Map.of(), false);

        assertThat(report.backend()).isEqualTo(new Endpoint("https", null, 9090, Scope.NETWORK, false));
        assertThat(report.servesFrontend()).isTrue();
        assertThat(report.bindSwitch()).isNull();
    }

    @Test
    @DisplayName("in a container the published address counts, not the 0.0.0.0:9090 inside")
    void containerReportsThePublishedAddress() {
        MockEnvironment properties = new MockEnvironment().withProperty("local.server.port", "9090");

        Report report = report(properties, Map.of(
                "MYSURICATES_RUNTIME", "container",
                "MYSURICATES_PUBLISHED_BIND", "0.0.0.0",
                "MYSURICATES_PUBLISHED_PORT", "8090",
                "MYSURICATES_BIND_SWITCH", "TEST_BIND"), false);

        assertThat(report.runtime()).isEqualTo("container");
        assertThat(report.backend()).isEqualTo(new Endpoint("http", null, 8090, Scope.NETWORK, true));
        assertThat(report.bindSwitch()).isEqualTo("TEST_BIND");
    }

    @Test
    @DisplayName("a container from an older starter script is unknown, never guessed")
    void containerWithoutPublishedAddressIsUnknown() {
        Report report = report(new MockEnvironment(), Map.of("MYSURICATES_PUBLISHED_PORT", "abc"), true);

        assertThat(report.runtime()).isEqualTo("container");
        assertThat(report.backend()).isEqualTo(new Endpoint("http", null, null, Scope.UNKNOWN, true));
    }

    @Test
    @DisplayName("the tryout reports its published loopback address")
    void tryout() {
        Report report = report(new MockEnvironment(), Map.of(
                "MYSURICATES_RUNTIME", "tryout",
                "MYSURICATES_PUBLISHED_BIND", "127.0.0.1",
                "MYSURICATES_PUBLISHED_PORT", "9095"), true);

        assertThat(report.runtime()).isEqualTo("tryout");
        assertThat(report.backend()).isEqualTo(new Endpoint("http", "127.0.0.1", 9095, Scope.LOCAL, true));
    }

    @Test
    @DisplayName("loopback in every spelling is this machine, anything else the network")
    void scopes() {
        assertThat(ListenAddresses.scopeOf("127.0.0.1")).isEqualTo(Scope.LOCAL);
        assertThat(ListenAddresses.scopeOf("127.0.1.1")).isEqualTo(Scope.LOCAL);
        assertThat(ListenAddresses.scopeOf("::1")).isEqualTo(Scope.LOCAL);
        assertThat(ListenAddresses.scopeOf("[::1]")).isEqualTo(Scope.LOCAL);
        assertThat(ListenAddresses.scopeOf("localhost")).isEqualTo(Scope.LOCAL);
        assertThat(ListenAddresses.scopeOf("")).isEqualTo(Scope.NETWORK);
        assertThat(ListenAddresses.scopeOf("0.0.0.0")).isEqualTo(Scope.NETWORK);
        assertThat(ListenAddresses.scopeOf("::")).isEqualTo(Scope.NETWORK);
        assertThat(ListenAddresses.scopeOf("192.168.0.181")).isEqualTo(Scope.NETWORK);
        assertThat(ListenAddresses.scopeOf("100.64.0.7")).isEqualTo(Scope.NETWORK);
    }
}
