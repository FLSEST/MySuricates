package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OutboundHttpTest {

    private final OutboundTargetGuard guard = mock(OutboundTargetGuard.class);
    private final OutboundHttp outboundHttp = new OutboundHttp(guard);

    @Test
    void client_carriesTheDeclaredTarget_andChecksWithTheGuardAndItsFlag() {
        OutboundClient client = outboundHttp.client(OutboundTarget.publicSource(), Duration.ofSeconds(3));
        URI uri = URI.create("https://feeds.example.org/cal.ics");

        client.checkTarget(uri);

        assertThat(client.target()).isEqualTo(OutboundTarget.publicSource());
        verify(guard).check(uri, ModuleCatalog.PRIVATE_NETWORK_GUARD);
    }

    @Test
    void client_passesARefusalOfTheGuardOn() {
        OutboundClient client = outboundHttp.client(OutboundTarget.ownService(), null);
        URI uri = URI.create("http://10.0.0.5/api");
        doThrow(new IllegalArgumentException("private address")).when(guard)
                .check(uri, ModuleCatalog.PRIVATE_NETWORK_GUARD_OWN_SERVICES);

        assertThatThrownBy(() -> client.checkTarget(uri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("private address");
    }

    @Test
    void client_forAFixedHost_neverAsksTheGuard() {
        OutboundClient client = outboundHttp.client(OutboundTarget.fixedHost(), null);

        client.checkTarget(URI.create("https://api.github.com/"));

        verifyNoInteractions(guard);
    }

    @Test
    void client_forAGuardedTarget_needsAGuard() {
        OutboundHttp withoutGuard = new OutboundHttp(null);

        assertThatThrownBy(() -> withoutGuard.client(OutboundTarget.publicSource(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("guard");
    }

    @Test
    void fixedHost_needsNoBeanAndNoGuard() {
        OutboundClient client = OutboundHttp.fixedHost(Duration.ofSeconds(1));

        assertThat(client.target()).isEqualTo(OutboundTarget.fixedHost());
        client.checkTarget(URI.create("http://127.0.0.1/"));   // no guard: nothing to refuse
    }
}
