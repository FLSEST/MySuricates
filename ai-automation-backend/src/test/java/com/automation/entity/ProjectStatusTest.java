package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectStatusTest {

    @Test
    void should_haveAllProjectStatusValuesInDeclaredOrder() {
        assertThat(ProjectStatus.values())
                .containsExactly(
                        ProjectStatus.STOPPED,
                        ProjectStatus.STARTING,
                        ProjectStatus.RUNNING,
                        ProjectStatus.ERROR
                );
    }

    @Test
    void should_resolveStoppedViaValueOf() {
        assertThat(ProjectStatus.valueOf("STOPPED")).isEqualTo(ProjectStatus.STOPPED);
    }

    @Test
    void should_resolveStartingViaValueOf() {
        assertThat(ProjectStatus.valueOf("STARTING")).isEqualTo(ProjectStatus.STARTING);
    }

    @Test
    void should_resolveRunningViaValueOf() {
        assertThat(ProjectStatus.valueOf("RUNNING")).isEqualTo(ProjectStatus.RUNNING);
    }

    @Test
    void should_resolveErrorViaValueOf() {
        assertThat(ProjectStatus.valueOf("ERROR")).isEqualTo(ProjectStatus.ERROR);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ProjectStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
