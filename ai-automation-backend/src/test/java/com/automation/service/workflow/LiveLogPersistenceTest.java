package com.automation.service.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveLogPersistenceTest {

    @Test
    void normalizeMapsDefaultAndBlankToNullAndKeepsOnOff() {
        assertThat(LiveLogPersistence.normalize(null)).isNull();
        assertThat(LiveLogPersistence.normalize("  ")).isNull();
        assertThat(LiveLogPersistence.normalize("Default")).isNull();
        assertThat(LiveLogPersistence.normalize(" ON ")).isEqualTo("on");
        assertThat(LiveLogPersistence.normalize("off")).isEqualTo("off");
    }

    @Test
    void normalizeRejectsUnknownValues() {
        assertThatThrownBy(() -> LiveLogPersistence.normalize("true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("default, on, off");
    }

    @Test
    void resolveLetsTheWorkflowOverrideTheSystemDefault() {
        assertThat(LiveLogPersistence.resolve(null, true)).isTrue();
        assertThat(LiveLogPersistence.resolve(null, false)).isFalse();
        assertThat(LiveLogPersistence.resolve("on", false)).isTrue();
        assertThat(LiveLogPersistence.resolve("off", true)).isFalse();
    }
}
