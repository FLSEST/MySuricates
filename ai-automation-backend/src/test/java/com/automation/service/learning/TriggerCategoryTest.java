package com.automation.service.learning;

import com.automation.dto.projection.DashboardRunRow;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** d75fbfef: webhook, chain and wish runs no longer count as manual; the webhook counter counts. */
class TriggerCategoryTest {

    private static WorkflowRun run(TriggerSource source) {
        return WorkflowRun.builder().id("r").triggerSource(source).build();
    }

    @Test
    void everyTriggerSourceHasItsCategory() {
        assertThat(TriggerCategory.of(run(TriggerSource.MANUAL))).isEqualTo(TriggerCategory.MANUAL);
        assertThat(TriggerCategory.of(run(TriggerSource.RETRY))).isEqualTo(TriggerCategory.MANUAL);
        assertThat(TriggerCategory.of(run(TriggerSource.ASSISTANT))).isEqualTo(TriggerCategory.MANUAL);
        assertThat(TriggerCategory.of(run(TriggerSource.SCHEDULE))).isEqualTo(TriggerCategory.SCHEDULED);
        assertThat(TriggerCategory.of(run(TriggerSource.WEBHOOK))).isEqualTo(TriggerCategory.WEBHOOK);
        assertThat(TriggerCategory.of(run(TriggerSource.COMPLETION_TRIGGER))).isEqualTo(TriggerCategory.COMPLETION_TRIGGER);
        assertThat(TriggerCategory.of(run(TriggerSource.TODO_TRIGGER))).isEqualTo(TriggerCategory.TODO_TRIGGER);
        for (TriggerSource source : new TriggerSource[] {
                TriggerSource.CHAIN, TriggerSource.SUB_WORKFLOW, TriggerSource.WISH, TriggerSource.AUTO_REPAIR,
                TriggerSource.AUTO_RESUME}) {
            assertThat(TriggerCategory.of(run(source))).as(source.name()).isEqualTo(TriggerCategory.OTHER_AUTOMATIC);
        }
    }

    @Test
    void runsWithoutTriggerSource_fallBackToTheOldClues() {
        assertThat(TriggerCategory.of(WorkflowRun.builder().triggeredByScheduleId("s-1").build()))
                .isEqualTo(TriggerCategory.SCHEDULED);
        assertThat(TriggerCategory.of(WorkflowRun.builder().metadata(Map.of("todoTriggerRun", true)).build()))
                .isEqualTo(TriggerCategory.TODO_TRIGGER);
        assertThat(TriggerCategory.of(WorkflowRun.builder().build())).isEqualTo(TriggerCategory.MANUAL);
    }

    @Test
    void distribution_mapsEveryCategoryAndZeroForMissingOnes() {
        Map<TriggerCategory, long[]> counts = new EnumMap<>(TriggerCategory.class);
        counts.put(TriggerCategory.WEBHOOK, new long[] {4});
        counts.put(TriggerCategory.OTHER_AUTOMATIC, new long[] {2});

        var distribution = TriggerCategory.distribution(counts);

        assertThat(distribution.webhook()).isEqualTo(4);
        assertThat(distribution.otherAutomatic()).isEqualTo(2);
        assertThat(distribution.manual()).isZero();
        assertThat(distribution.scheduled()).isZero();
    }

    /** The dashboard's projected row counts the same way (TODO 2ccb9e40). */
    @Test
    void projectedRowsCountLikeRuns() {
        assertThat(TriggerCategory.of(row(TriggerSource.WISH, null, null))).isEqualTo(TriggerCategory.OTHER_AUTOMATIC);
        assertThat(TriggerCategory.of(row(null, "s-1", null))).isEqualTo(TriggerCategory.SCHEDULED);
        assertThat(TriggerCategory.of(row(null, null, "true"))).isEqualTo(TriggerCategory.TODO_TRIGGER);
        assertThat(TriggerCategory.of(row(null, null, "text"))).isEqualTo(TriggerCategory.MANUAL);
        assertThat(TriggerCategory.of(row(null, null, null))).isEqualTo(TriggerCategory.MANUAL);
    }

    private static DashboardRunRow row(TriggerSource source, String scheduleId, String todoTriggerRunType) {
        return new DashboardRunRow("r", null, null, null, null, source, scheduleId, null, null, null, null, null,
                null, todoTriggerRunType, null);
    }
}
