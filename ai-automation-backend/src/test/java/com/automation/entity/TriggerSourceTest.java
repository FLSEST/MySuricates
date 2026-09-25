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

    /** Only a run the chain executor started records the chain run; the key alone proves nothing. */
    @Test
    void chainRunIdIsReadOnlyForChainRuns() {
        Map<String, Object> entryContext = Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, "chain-run-7");

        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, entryContext)).isEqualTo("chain-run-7");
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.RETRY, entryContext)).as("a retry keeps the context").isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.SUB_WORKFLOW, entryContext)).as("inherited from the parent").isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.MANUAL, entryContext)).isNull();
        assertThat(TriggerSource.chainRunIdOf(null, entryContext)).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, null)).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, " "))).isNull();
        assertThat(TriggerSource.chainRunIdOf(TriggerSource.CHAIN, Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, 42))).isNull();
    }
}
