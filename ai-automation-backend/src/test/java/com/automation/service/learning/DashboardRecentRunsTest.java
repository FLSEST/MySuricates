package com.automation.service.learning;

import com.automation.dto.projection.DashboardRunRow;
import com.automation.dto.response.DashboardResponse.RecentRunEntry;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardRecentRunsTest {

    private static final Instant START = Instant.parse("2026-10-07T08:00:00Z");

    private static DashboardRunRow row(String id, String snapshot, String workflowName) {
        return new DashboardRunRow(id, RunStatus.COMPLETED, START, START.plusSeconds(5), 5000L,
                TriggerSource.MANUAL, null, snapshot, "wf-" + id, workflowName, "p1", "Project",
                null, null, null);
    }

    @Test
    void emptyWindow_givesNoEntries() {
        assertThat(DashboardRecentRuns.of(List.of())).isEmpty();
    }

    @Test
    void entry_carriesTheWidgetFields() {
        RecentRunEntry entry = DashboardRecentRuns.of(List.of(row("r1", "Snapshot name", "Current name"))).get(0);

        assertThat(entry).isEqualTo(new RecentRunEntry("r1", "wf-r1", "Snapshot name", "Project",
                RunStatus.COMPLETED, START, START.plusSeconds(5), 5000L));
    }

    @Test
    void name_fallsBackFromSnapshotToCurrentNameToDeleted() {
        List<RecentRunEntry> entries = DashboardRecentRuns.of(List.of(
                row("a", null, "Current"),
                row("b", "  ", "Current"),
                row("c", null, null)));

        assertThat(entries).extracting(RecentRunEntry::workflowName)
                .containsExactly("Current", "Current", "(deleted)");
    }

    @Test
    void keepsTheOrder_andStopsAtTheLimit() {
        List<DashboardRunRow> window = IntStream.range(0, 15).mapToObj(i -> row("r" + i, "n", "n")).toList();

        List<RecentRunEntry> entries = DashboardRecentRuns.of(window);

        assertThat(DashboardRecentRuns.LIMIT).isEqualTo(10);
        assertThat(entries).extracting(RecentRunEntry::id)
                .containsExactly("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9");
    }
}
