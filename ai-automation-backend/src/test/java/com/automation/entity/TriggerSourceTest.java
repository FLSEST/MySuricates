package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerSourceTest {

    @Test
    void directSourcesAreTheirOwnEffectiveSource() {
        assertThat(TriggerSource.WEBHOOK.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL")))
                .isEqualTo(TriggerSource.WEBHOOK);
        assertThat(TriggerSource.SCHEDULE.unattended()).isTrue();
        assertThat(TriggerSource.MANUAL.unattended()).isFalse();
    }

    @Test
    void inheritingSourcesTakeTheParentFromTheReservedKey() {
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE")))
                .isEqualTo(TriggerSource.SCHEDULE);
        assertThat(TriggerSource.SUB_WORKFLOW.effective(Map.of())).isNull();
        assertThat(TriggerSource.SUB_WORKFLOW.effective(null)).isNull();
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "nonsense"))).isNull();
        assertThat(TriggerSource.CHAIN.effective(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "CHAIN"))).isNull();
    }

    @Test
    void effectiveSourceOfARunFollowsNesting() {
        Map<String, Object> chainEntryRun = new HashMap<>();
        chainEntryRun.put("trigger_source", "CHAIN");
        chainEntryRun.put(TriggerSource.PARENT_CONTEXT_KEY, "WISH");
        assertThat(TriggerSource.effectiveOfRun(chainEntryRun)).isEqualTo(TriggerSource.WISH);

        assertThat(TriggerSource.effectiveOfRun(Map.of("trigger_source", "MANUAL",
                TriggerSource.PARENT_CONTEXT_KEY, "WEBHOOK"))).isEqualTo(TriggerSource.MANUAL);
        assertThat(TriggerSource.effectiveOfRun(Map.of("trigger_source", ""))).isNull();
    }
}
