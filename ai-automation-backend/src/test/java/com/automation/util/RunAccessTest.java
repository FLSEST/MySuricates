package com.automation.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunAccessTest {

    private static final RunAccess.Grant GRANT = new RunAccess.Grant("tok-123", "http://localhost:9090/api/v1");

    @AfterEach
    void tearDown() {
        RunAccess.clear();
        RunAccess.unregister(GRANT.token());
    }

    @Test
    @DisplayName("a bound grant puts token, API base and a local no_proxy entry into the environment")
    void boundGrantFillsTheEnvironment() {
        RunAccess.bind(GRANT);
        Map<String, String> env = new HashMap<>(Map.of("no_proxy", "example.org"));

        RunAccess.addTo(env);

        assertThat(env).containsEntry(RunAccess.TOKEN_ENV, "tok-123")
                .containsEntry(RunAccess.API_ENV, "http://localhost:9090/api/v1")
                .containsEntry("no_proxy", "example.org,localhost,127.0.0.1,::1")
                .containsEntry("NO_PROXY", "localhost,127.0.0.1,::1");
    }

    @Test
    @DisplayName("a backend bound to another address is reached there directly, never through a proxy")
    void boundAddressJoinsNoProxy() {
        RunAccess.bind(new RunAccess.Grant("tok-123", RunAccess.localApiBase("10.0.0.5", "9090")));
        Map<String, String> env = new HashMap<>();

        RunAccess.addTo(env);

        assertThat(env).containsEntry(RunAccess.API_ENV, "http://10.0.0.5:9090/api/v1")
                .containsEntry("no_proxy", "localhost,127.0.0.1,::1,10.0.0.5");
    }

    @Test
    @DisplayName("the API base is the backend's own port on loopback, whatever the instance URL says")
    void localApiBase() {
        assertThat(RunAccess.localApiBase(null, "9090")).isEqualTo("http://127.0.0.1:9090/api/v1");
        assertThat(RunAccess.localApiBase(" 0.0.0.0 ", "9090")).isEqualTo("http://127.0.0.1:9090/api/v1");
        assertThat(RunAccess.localApiBase("::", "9091")).isEqualTo("http://127.0.0.1:9091/api/v1");
        assertThat(RunAccess.localApiBase("127.0.0.1", "9090")).isEqualTo("http://127.0.0.1:9090/api/v1");
        assertThat(RunAccess.localApiBase("::1", "9090")).isEqualTo("http://[::1]:9090/api/v1");
    }

    @Test
    @DisplayName("without a bound grant the environment stays as it is")
    void unboundThreadAddsNothing() {
        Map<String, String> env = new HashMap<>();

        RunAccess.addTo(env);

        assertThat(env).isEmpty();
    }

    @Test
    @DisplayName("the binding belongs to the thread: another thread (a sub-workflow run) does not see it")
    void bindingIsNotInherited() throws InterruptedException {
        RunAccess.bind(GRANT);
        AtomicReference<Boolean> seen = new AtomicReference<>();
        Thread child = Thread.ofVirtual().start(() -> seen.set(RunAccess.current().isPresent()));
        child.join();

        assertThat(seen.get()).isFalse();
        assertThat(RunAccess.current()).contains(GRANT);
    }

    @Test
    @DisplayName("live token values are masked, revoked ones no longer")
    void masksLiveTokens() {
        RunAccess.register(GRANT.token());

        assertThat(RunAccess.mask("token=tok-123 and again tok-123")).isEqualTo("token=*** and again ***");
        assertThat(RunAccess.mask(null)).isNull();

        RunAccess.unregister(GRANT.token());
        assertThat(RunAccess.mask("tok-123")).isEqualTo("tok-123");
    }

    @Test
    @DisplayName("maskValues masks the string values of a payload and keeps the rest")
    void masksPayloadValues() {
        RunAccess.register(GRANT.token());

        assertThat(RunAccess.maskValues(Map.of("output", "a tok-123", "index", 2)))
                .containsEntry("output", "a ***").containsEntry("index", 2);
    }
}
