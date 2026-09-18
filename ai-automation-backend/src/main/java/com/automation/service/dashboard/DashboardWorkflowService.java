package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.dto.response.DashboardWorkflowResultResponse.RunSummary;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read side of the dashboard's workflow widgets (spec E3): the latest run of each requested workflow
 * and the contract it reported. Pure DB reads of two lean projections, so plain @Transactional is
 * fine — and no step output is ever loaded (pool-size = 1).
 *
 * <p>The stored contract is validated a second time here. Whatever put it into the run metadata,
 * only something that matches the contract exactly reaches the browser.
 */
@Service
@RequiredArgsConstructor
public class DashboardWorkflowService {

    /** One widget instance shows one workflow; a view with more than this is a client bug, not a use case. */
    public static final int MAX_WORKFLOWS = 50;

    /** Same id shape the dashboard layout accepts (DashboardLayoutRules) — workflow ids are lower-case uuids. */
    private static final Pattern WORKFLOW_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;

    @Transactional(readOnly = true)
    public List<DashboardWorkflowResultResponse> results(List<String> workflowIds) {
        List<String> ids = validIds(workflowIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Object[] row : workflowRepository.findIdAndNameByIdIn(ids)) {
            names.put((String) row[0], (String) row[1]);
        }
        Map<String, Object[]> latestRuns = new HashMap<>();
        for (Object[] row : runRepository.findLatestRunPerWorkflow(ids)) {
            // Ordered newest first; two runs can share a start time, the first one wins.
            latestRuns.putIfAbsent((String) row[0], row);
        }
        return ids.stream().map(id -> toResponse(id, names.get(id), latestRuns.get(id))).toList();
    }

    /** Distinct ids in request order; anything outside the pattern or over the limit is a 400. */
    private static List<String> validIds(List<String> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return List.of();
        }
        if (workflowIds.size() > MAX_WORKFLOWS) {
            throw new IllegalArgumentException("workflowIds must hold at most " + MAX_WORKFLOWS + " ids");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : workflowIds) {
            if (id == null || !WORKFLOW_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("workflowIds must match " + WORKFLOW_ID.pattern());
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static DashboardWorkflowResultResponse toResponse(String workflowId, String name, Object[] row) {
        if (name == null) {
            // Soft-deleted or unknown: no run data and no result for a workflow that is gone.
            return new DashboardWorkflowResultResponse(workflowId, null, false, null, ResultState.MISSING, null);
        }
        if (row == null) {
            return new DashboardWorkflowResultResponse(workflowId, name, true, null, ResultState.MISSING, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = row[6] != null ? (Map<String, Object>) row[6] : Map.of();
        RunSummary run = new RunSummary((String) row[1], (RunStatus) row[2], (Instant) row[3], (Instant) row[4],
                (Long) row[5], gateStatus(metadata.get("gate_status")));
        DashboardWidgetResultParser.ParseResult parsed =
                DashboardWidgetResultParser.fromMetadata(metadata.get(DashboardWidgetResultParser.METADATA_KEY));
        ResultState state = parsed.state() == ResultState.MISSING
                && metadata.containsKey(DashboardWidgetResultParser.ERROR_METADATA_KEY)
                ? ResultState.INVALID
                : parsed.state();
        return new DashboardWorkflowResultResponse(workflowId, name, true, run, state, parsed.result());
    }

    private static GateStatus gateStatus(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return GateStatus.valueOf(String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
