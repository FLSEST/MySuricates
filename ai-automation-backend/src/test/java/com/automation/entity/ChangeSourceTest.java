package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** key() landet in workflow_versions.change_source — die gespeicherten Werte duerfen nicht driften. */
class ChangeSourceTest {

    @Test
    void key_isTheStoredLowercaseValue() {
        assertThat(ChangeSource.USER.key()).isEqualTo("user");
        assertThat(ChangeSource.FACTORY.key()).isEqualTo("factory");
        assertThat(ChangeSource.CHAIN_TEMPLATE.key()).isEqualTo("chain-template");
        assertThat(ChangeSource.POOL_INSTALL.key()).isEqualTo("pool-install");
        assertThat(ChangeSource.BPMN_IMPORT.key()).isEqualTo("bpmn-import");
        assertThat(ChangeSource.UNTRACKED.key()).isEqualTo("untracked");
    }

    @Test
    void keys_areUniqueAndDerivedFromTheConstantName() {
        assertThat(Arrays.stream(ChangeSource.values()).map(ChangeSource::key)).doesNotHaveDuplicates();
        for (ChangeSource source : ChangeSource.values()) {
            assertThat(source.key()).isEqualTo(source.name().toLowerCase().replace('_', '-'));
        }
    }

    @Test
    void keys_neverCollideWithLegacyValues() {
        // Older rows still carry "ai" or "api" from the time before the enum.
        assertThat(Arrays.stream(ChangeSource.values()).map(ChangeSource::key)).doesNotContain("ai", "api");
    }
}
