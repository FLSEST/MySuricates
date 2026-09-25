package com.automation.dto.projection;

import com.automation.entity.TriggerSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The columns of {@code WorkflowRunProjectionRepository.findOriginColumns} as a row (TODO a3b89355). */
class RunOriginRowTest {

    @Test
    void readsTheColumnsInQueryOrder() {
        RunOriginRow row = RunOriginRow.fromColumns(new Object[]{"run-1", "CHAIN", "sched-1", "cr-1", "v-1", "SCHEDULE"});

        assertThat(row).isEqualTo(new RunOriginRow("run-1", TriggerSource.CHAIN, "sched-1", "cr-1", "v-1", "SCHEDULE"));
    }

    /** The query returns '' for "no parent" (column stays TEXT); an unknown or missing source is null. */
    @Test
    void emptyParentAndUnknownSourceReadAsNull() {
        RunOriginRow row = RunOriginRow.fromColumns(new Object[]{"run-1", "NO_SUCH_SOURCE", null, null, null, ""});
        RunOriginRow beforeProvenance = RunOriginRow.fromColumns(new Object[]{"run-2", null, null, null, null, " "});

        assertThat(row.triggerSource()).isNull();
        assertThat(row.parentTriggerSource()).isNull();
        assertThat(beforeProvenance.triggerSource()).isNull();
        assertThat(beforeProvenance.parentTriggerSource()).isNull();
    }
}
