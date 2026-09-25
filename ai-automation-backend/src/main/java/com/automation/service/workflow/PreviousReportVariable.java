package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.util.UntrustedText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code previous_report}: the analysis of the last completed run of the same workflow. Moved out of
 * {@link WorkflowContextDataService} (TODO 441a4c46).
 *
 * <p>The report is an earlier model output and is handed to the next model, which may echo it — so it
 * loses what a parser would act on (TODO 3bf8c9d8). The audit envelope is cut off entirely: machine JSON
 * of the old snapshot, no use for reading a trend (the findings' current state is {@code audit_findings}),
 * and exactly the part a model could copy along with its marker. Every other marker keeps its line but
 * loses its colon ({@link UntrustedText#defuseLines}): {@code gate_status GREEN} still reads as the last
 * verdict, but no parser takes it for the new run's own when it is repeated.
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
class PreviousReportVariable implements PlatformVariableSource {

    static final String NAME = "previous_report";
    static final String NONE = "(kein vorheriger Report vorhanden)";
    static final int MAX_CHARS = 3000;

    /**
     * An audit result envelope, begun as {@code AuditResultParser} sees one begin (marker and JSON start —
     * a marker quoted in prose is none, and {@link UntrustedText#defuseLines} takes its colon), also one
     * that never closes: from its marker to the end of the comment, or else to the end of the text.
     */
    private static final Pattern ENVELOPE =
            Pattern.compile("<!--\\s*mysuricates:audit-result:v1\\s*\\{.*?(?:-->|\\z)", Pattern.DOTALL);

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
     * Returns the longest non-shell step output (the Opus analysis), {@link #defused} and truncated to 3000 chars.
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
                    return defused(bestOutput);
                }
            }
        } catch (Exception e) {
            log.debug("Could not load previous report for workflow {}: {}", workflowId, e.getMessage());
        }
        return NONE;
    }

    /**
     * Envelope cut off before truncating, so the report text gets its room; markers defused after it.
     * A report that was nothing but an envelope is no report.
     */
    static String defused(String output) {
        String report = ENVELOPE.matcher(output).replaceAll("");
        if (report.isBlank()) {
            return NONE;
        }
        if (report.length() > MAX_CHARS) {
            report = report.substring(0, MAX_CHARS) + "\n[...gekürzt]";
        }
        return UntrustedText.defuseLines(report);
    }
}
