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
    public List<String> allNames() {
        return List.of(NAME);
    }

    @Override
    public List<String> names(Request request) {
        return allNames();
    }

    @Override
    public Map<String, String> load(Request request) {
        return Map.of(NAME, value(request.workflowId(), reportBlock(request.workflowMeta())));
    }

    /**
     * The block an audit workflow names as its report ({@code metadata.audit.reportBlock}, the block whose
     * output {@code AuditIngestionService} ingests), or {@code null} when the workflow names none.
     */
    static String reportBlock(Map<String, Object> workflowMeta) {
        if (workflowMeta == null || !(workflowMeta.get("audit") instanceof Map<?, ?> audit)) return null;
        Object block = audit.get("reportBlock");
        return block instanceof String name && !name.isBlank() ? name : null;
    }

    String value(String workflowId) {
        return value(workflowId, null);
    }

    /**
     * Loads the analysis report from the last completed run of the same workflow, {@link #defused} and
     * truncated to 3000 chars. With a {@code reportBlock} it is that block's output (75f7b24d): the longest
     * output could be a fact sheet of a shell block — in the health check the test log; a run whose report
     * block did not complete has no report. Without one it is the longest completed output after the first
     * step (the shell data collection), when it is longer than 200 chars.
     */
    String value(String workflowId, String reportBlock) {
        try {
            List<WorkflowRun> previousRuns = runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            for (WorkflowRun prev : previousRuns) {
                if (prev.getStatus() != RunStatus.COMPLETED && prev.getStatus() != RunStatus.COMPLETED_WITH_ERRORS) {
                    continue;
                }
                List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(prev.getId());
                String report = reportBlock != null ? reportBlockOutput(steps, reportBlock) : longestOutput(steps);
                if (report != null) {
                    return defused(report);
                }
            }
        } catch (Exception e) {
            log.debug("Could not load previous report for workflow {}: {}", workflowId, e.getMessage());
        }
        return NONE;
    }

    private static String reportBlockOutput(List<StepResult> steps, String reportBlock) {
        for (StepResult step : steps) {
            Map<String, Object> meta = step.getMetadata();
            if (meta == null || !reportBlock.equals(meta.get("blockName"))) continue;
            String output = step.getOutput();
            return step.getStatus() == StepStatus.COMPLETED && output != null && !output.isBlank() ? output : null;
        }
        return null;
    }

    /** The longest completed non-first step output (the first is the shell data collection), if > 200 chars. */
    private static String longestOutput(List<StepResult> steps) {
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
        return bestLength > 200 ? bestOutput : null;
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
