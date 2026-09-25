package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code previous_report}: the analysis of the last completed run of the same workflow. Moved out of
 * {@link WorkflowContextDataService} (TODO 441a4c46).
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
class PreviousReportVariable implements PlatformVariableSource {

    static final String NAME = "previous_report";

    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;

    @Override
    public List<String> names(Request request) {
        return List.of(NAME);
    }

    @Override
    public Map<String, String> load(Request request) {
        return Map.of(NAME, value(request.workflowId()));
    }

    /**
     * Loads the analysis report from the last completed run of the same workflow.
     * Returns the longest non-shell step output (the Opus analysis), truncated to 3000 chars.
     */
    String value(String workflowId) {
        try {
            List<WorkflowRun> previousRuns = runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            for (WorkflowRun prev : previousRuns) {
                if (prev.getStatus() != RunStatus.COMPLETED && prev.getStatus() != RunStatus.COMPLETED_WITH_ERRORS) {
                    continue;
                }
                List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(prev.getId());
                // Find the longest completed non-first step output (skip shell data-collection block)
                String bestOutput = null;
                int bestLength = 0;
                for (int i = 1; i < steps.size(); i++) {
                    StepResult step = steps.get(i);
                    if (step.getStatus() != StepStatus.COMPLETED) continue;
                    String output = step.getOutput();
                    if (output != null && output.length() > bestLength) {
                        bestLength = output.length();
                        bestOutput = output;
                    }
                }
                if (bestOutput != null && bestLength > 200) {
                    return bestLength > 3000 ? bestOutput.substring(0, 3000) + "\n[...gekürzt]" : bestOutput;
                }
            }
        } catch (Exception e) {
            log.debug("Could not load previous report for workflow {}: {}", workflowId, e.getMessage());
        }
        return "(kein vorheriger Report vorhanden)";
    }
}
