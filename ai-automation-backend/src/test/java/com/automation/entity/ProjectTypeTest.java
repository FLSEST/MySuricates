package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTypeTest {

    @Test
    void should_haveAllProjectTypeValuesInDeclaredOrder() {
        assertThat(ProjectType.values())
                .containsExactly(
                        ProjectType.CODE,
                        ProjectType.CONTENT,
                        ProjectType.RESEARCH,
                        ProjectType.BUSINESS,
                        ProjectType.PERSONAL
                );
    }

    @Test
    void should_resolveCodeViaValueOf() {
        assertThat(ProjectType.valueOf("CODE")).isEqualTo(ProjectType.CODE);
    }

    @Test
    void should_resolveContentViaValueOf() {
        assertThat(ProjectType.valueOf("CONTENT")).isEqualTo(ProjectType.CONTENT);
    }

    @Test
    void should_resolveResearchViaValueOf() {
        assertThat(ProjectType.valueOf("RESEARCH")).isEqualTo(ProjectType.RESEARCH);
    }

    @Test
    void should_resolveBusinessViaValueOf() {
        assertThat(ProjectType.valueOf("BUSINESS")).isEqualTo(ProjectType.BUSINESS);
    }

    @Test
    void should_resolvePersonalViaValueOf() {
        assertThat(ProjectType.valueOf("PERSONAL")).isEqualTo(ProjectType.PERSONAL);
    }

    @Test
    void should_throwException_whenValueOfReceivesInvalidName() {
        assertThatThrownBy(() -> ProjectType.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
