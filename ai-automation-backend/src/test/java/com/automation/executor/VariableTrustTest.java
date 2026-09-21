package com.automation.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableTrustTest {

    private final VariableTrust trust = new VariableTrust();

    @Test
    void freshTrust_hasNothingUntrusted() {
        assertThat(trust.isUntrusted("x")).isFalse();
        assertThat(trust.isPlatform("x")).isFalse();
        assertThat(trust.hasExternalInput()).isFalse();
        assertThat(trust.escapedNames()).isEmpty();
        assertThat(trust.externalNames()).isEmpty();
    }

    @Test
    void external_isEscapedPersistedAndTaintsFromTheStart() {
        trust.markUntrusted("payload");

        assertThat(trust.isUntrusted("payload")).isTrue();
        assertThat(trust.isPlatform("payload")).isFalse();
        assertThat(trust.hasExternalInput()).isTrue();
        assertThat(trust.escapedNames()).containsExactly("payload");
        assertThat(trust.externalNames()).containsExactly("payload");
    }

    @Test
    void platform_isEscapedButNeitherPersistedNorTaintingOnInjection() {
        trust.markPlatform("previous_report");

        assertThat(trust.isUntrusted("previous_report")).isTrue();
        assertThat(trust.isPlatform("previous_report")).isTrue();
        assertThat(trust.hasExternalInput()).isFalse();
        assertThat(trust.escapedNames()).containsExactly("previous_report");
        assertThat(trust.externalNames()).isEmpty();
    }

    @Test
    void classesAreExclusive_lastWriteWins() {
        trust.markUntrusted("v");
        trust.markPlatform("v");
        assertThat(trust.isPlatform("v")).isTrue();
        assertThat(trust.externalNames()).isEmpty();

        trust.markUntrusted("v");
        assertThat(trust.isPlatform("v")).isFalse();
        assertThat(trust.externalNames()).containsExactly("v");
    }

    @Test
    void clear_removesBothClasses() {
        trust.markUntrusted("a");
        trust.markPlatform("b");
        trust.clear("a");
        trust.clear("b");

        assertThat(trust.isUntrusted("a")).isFalse();
        assertThat(trust.isUntrusted("b")).isFalse();
        assertThat(trust.escapedNames()).isEmpty();
    }

    @Test
    void escapedNames_isUnionAndImmutableSnapshot() {
        trust.markUntrusted("a");
        trust.markPlatform("b");

        var names = trust.escapedNames();
        trust.markUntrusted("c");

        assertThat(names).containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(() -> names.add("z")).isInstanceOf(UnsupportedOperationException.class);
    }
}
