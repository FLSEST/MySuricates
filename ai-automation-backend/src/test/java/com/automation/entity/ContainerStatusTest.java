package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerStatusTest {

    @Test
    void should_haveAllContainerStatusValuesInDeclaredOrder() {
        assertThat(ContainerStatus.values())
                .containsExactly(
                        ContainerStatus.CREATED,
                        ContainerStatus.RUNNING,
                        ContainerStatus.STOPPED,
                        ContainerStatus.ERROR
                );
    }

    @Test
    void should_resolveCreatedViaValueOf() {
        assertThat(ContainerStatus.valueOf("CREATED")).isEqualTo(ContainerStatus.CREATED);
    }

    @Test
    void should_resolveRunningViaValueOf() {
        assertThat(ContainerStatus.valueOf("RUNNING")).isEqualTo(ContainerStatus.RUNNING);
    }

    @Test
    void should_resolveStoppedViaValueOf() {
        assertThat(ContainerStatus.valueOf("STOPPED")).isEqualTo(ContainerStatus.STOPPED);
    }

    @Test
    void should_resolveErrorViaValueOf() {
        assertThat(ContainerStatus.valueOf("ERROR")).isEqualTo(ContainerStatus.ERROR);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ContainerStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
