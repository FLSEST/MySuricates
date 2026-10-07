package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundTargetTest {

    @Test
    void fixedHost_hasNoFlag_andIsNotGuarded() {
        OutboundTarget target = OutboundTarget.fixedHost();

        assertThat(target.kind()).isEqualTo(OutboundTarget.Kind.FIXED_HOST);
        assertThat(target.flag()).isNull();
        assertThat(target.guarded()).isFalse();
    }

    @Test
    void publicSource_isGuardedByThePrivateNetworkGuard() {
        OutboundTarget target = OutboundTarget.publicSource();

        assertThat(target.kind()).isEqualTo(OutboundTarget.Kind.PUBLIC_SOURCE);
        assertThat(target.flag()).isEqualTo(ModuleCatalog.PRIVATE_NETWORK_GUARD);
        assertThat(target.guarded()).isTrue();
    }

    @Test
    void ownService_isGuardedByTheOwnServicesFlag_byDefault() {
        OutboundTarget target = OutboundTarget.ownService();

        assertThat(target.kind()).isEqualTo(OutboundTarget.Kind.OWN_SERVICE);
        assertThat(target.flag()).isEqualTo(ModuleCatalog.PRIVATE_NETWORK_GUARD_OWN_SERVICES);
        assertThat(target.guarded()).isTrue();
    }

    @Test
    void ownService_withItsOwnFlag_usesThatFlag() {
        OutboundTarget target = OutboundTarget.ownService(ModuleCatalog.PRIVATE_NETWORK_GUARD_HTTP_API);

        assertThat(target.kind()).isEqualTo(OutboundTarget.Kind.OWN_SERVICE);
        assertThat(target.flag()).isEqualTo(ModuleCatalog.PRIVATE_NETWORK_GUARD_HTTP_API);
    }

    @Test
    void ownService_withANullFlag_isRejected() {
        assertThatThrownBy(() -> OutboundTarget.ownService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("flag");
    }

    @Test
    void kind_isRequired() {
        assertThatThrownBy(() -> new OutboundTarget(null, ModuleCatalog.PRIVATE_NETWORK_GUARD))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("kind");
    }

    @Test
    void aFixedHostWithAFlag_isRejected() {
        assertThatThrownBy(() -> new OutboundTarget(OutboundTarget.Kind.FIXED_HOST, ModuleCatalog.PRIVATE_NETWORK_GUARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A fixed host has no guard flag; every other kind needs one");
    }

    @Test
    void aGuardedKindWithoutAFlag_isRejected() {
        assertThatThrownBy(() -> new OutboundTarget(OutboundTarget.Kind.PUBLIC_SOURCE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundTarget(OutboundTarget.Kind.OWN_SERVICE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
