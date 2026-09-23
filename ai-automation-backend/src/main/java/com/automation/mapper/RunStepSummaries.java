package com.automation.mapper;

import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the {@link RunStepSummary} of a page of runs without loading a single step row: one
 * GROUP-BY query counts the steps, one cuts the last output in the database (TODO 7fd9f654).
 * A list of 50 runs costs two statements, and no step blob crosses the single SQLite connection.
 */
@Component
@RequiredArgsConstructor
public class RunStepSummaries {

    private final StepResultRepository stepResultRepository;

    /** Run id -> summary; runs without steps are missing (use {@link RunStepSummary#NONE}). */
    public Map<String, RunStepSummary> of(List<WorkflowRun> runs) {
        Set<String> runIds = runs.stream().map(WorkflowRun::getId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = stepResultRepository.countStepsByRunIds(runIds).stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).intValue()));
        Map<String, RunStepSummary> summaries = new HashMap<>();
        for (Object[] row : stepResultRepository.findLastStepHeadsByRunIds(runIds, RunStepSummary.HEAD_LENGTH)) {
            String runId = (String) row[0];
            summaries.put(runId, new RunStepSummary(counts.getOrDefault(runId, 0), (StepStatus) row[1], (String) row[2]));
        }
        return summaries;
    }
}
