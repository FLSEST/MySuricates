package com.automation.dto.response;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What one dashboard workflow widget shows (spec E3): the latest run of a workflow and — for the
 * result widget — the contract that run reported. Never step output, never any text that is not part
 * of the validated contract.
 *
 * @param workflowId   the requested workflow id, echoed so the client can match the entry
 * @param workflowName live name; null when the workflow no longer exists
 * @param available    false for a soft-deleted or unknown workflow; then run and result stay empty
 * @param run          lean facts of the latest run, null when there is none
 * @param resultState  whether the latest run reported a valid contract
 * @param result       only set when resultState is VALID
 */
public record DashboardWorkflowResultResponse(
        String workflowId,
        String workflowName,
        boolean available,
        RunSummary run,
        ResultState resultState,
        Result result) {

    /** Lean run facts — no step output, no error text, no free-form strings from the run. */
    public record RunSummary(String runId, RunStatus status, Instant startedAt, Instant completedAt,
                             Long durationMs, GateStatus gateStatus) {
    }

    public enum ResultState { VALID, INVALID, MISSING }

    /**
     * The closed contract of the {@code dashboard_widget:} marker. It holds no ids and no URLs on
     * purpose: nothing inside it can ever become a link or a navigation target.
     *
     * <p>The type carries its own guarantees (fix round 1, A4), so a caller building one by hand
     * (not through {@code DashboardWidgetResultParser}) cannot leak a null onto a read path: a
     * status is required, and {@code kpis}/{@code items} are never null — a null argument becomes an
     * empty, immutable list.
     */
    public record Result(String title, Status status, List<Kpi> kpis, List<String> items, String summary) {

        public Result {
            Objects.requireNonNull(status, "Result.status must not be null");
            kpis = kpis == null ? List.of() : List.copyOf(kpis);
            items = items == null ? List.of() : List.copyOf(items);
        }

        public enum Status { GREEN, YELLOW, RED }

        public enum Trend { UP, DOWN, FLAT }

        public record Kpi(String label, String value, Trend trend) {

            public Kpi {
                Objects.requireNonNull(label, "Kpi.label must not be null");
                Objects.requireNonNull(value, "Kpi.value must not be null");
            }
        }
    }
}
