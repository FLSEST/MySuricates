package com.automation.service.assistant.fetch;

import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.assistant.format.AssistantFormatUtils;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fetch handler for the run domain: run details, a workflow's recent runs and
 * paged step outputs ({@code run_detail}, {@code workflow_runs}, {@code step_output}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunFetchHandler {

    private final WorkflowRunRepository workflowRunRepository;

    public String formatRunDetail(String id) {
        try {
            return workflowRunRepository.findByIdWithSteps(id).map(run -> {
                var sb = new StringBuilder();
                sb.append("**Run ").append(id, 0, Math.min(8, id.length())).append("…** — ");
                sb.append(AssistantFormatUtils.runLabel(run)).append(", Status: ").append(run.getStatus());
                if (run.getDurationMs() != null)
                    sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
                sb.append("\n");
                String err = AssistantFormatUtils.extractError(run);
                if (err != null) sb.append("Run error: ").append(TruncationUtil.smart(err, 200, 400)).append("\n");
                var steps = run.getSteps();
                if (!steps.isEmpty()) {
                    sb.append("Steps:\n");
                    steps.forEach(s -> {
                        sb.append("  ").append(s.getStepIndex() + 1).append(". ").append(s.getStatus());
                        if (s.getStatus() == StepStatus.FAILED && s.getOutput() != null)
                            sb.append(": ").append(TruncationUtil.smart(s.getOutput(), 200, 600));
                        sb.append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Run " + id + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch run detail for {}", id, e);
            return "";
        }
    }

    public String formatWorkflowRuns(String workflowId) {
        try {
            List<WorkflowRun> runs = workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            if (runs.isEmpty()) return "No runs for workflow " + workflowId + ".\n";
            var sb = new StringBuilder();
            sb.append("**Recent runs for workflow ").append(workflowId, 0, Math.min(8, workflowId.length()))
                    .append("…:**\n");
            runs.forEach(r -> {
                sb.append("  - [").append(AssistantFormatUtils.fmt(r.getStartedAt())).append("] ")
                        .append(r.getStatus()).append(" (link:").append(AssistantFormatUtils.runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = AssistantFormatUtils.extractError(r);
                    if (err != null) sb.append(" — ").append(AssistantFormatUtils.truncate(err, 100));
                }
                sb.append("\n");
            });
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not fetch workflow runs for {}", workflowId, e);
            return "";
        }
    }

    public String formatStepOutput(String idAndStep) {
        // Optional paging suffix: <runId>/<stepNumber>?offset=<n> or ?tail=<n>
        String idPart = idAndStep;
        String paging = null;
        int q = idAndStep.indexOf('?');
        if (q >= 0) {
            idPart = idAndStep.substring(0, q);
            paging = idAndStep.substring(q + 1);
        }
        int slash = idPart.lastIndexOf('/');
        if (slash < 0) return "Invalid format. Expected: <runId>/<stepNumber>\n";
        String runId = idPart.substring(0, slash);
        int stepNum;
        try {
            stepNum = Integer.parseInt(idPart.substring(slash + 1));
        } catch (NumberFormatException e) {
            return "Invalid step number: " + idPart.substring(slash + 1) + "\n";
        }
        Integer offset = null;
        Integer tailChars = null;
        if (paging != null) {
            try {
                if (paging.startsWith("offset=")) {
                    offset = Integer.parseInt(paging.substring("offset=".length()));
                } else if (paging.startsWith("tail=")) {
                    tailChars = Integer.parseInt(paging.substring("tail=".length()));
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return "Invalid paging parameter: " + paging + ". Expected ?offset=<int> or ?tail=<int>\n";
            }
        }
        final Integer windowOffset = offset;
        final Integer tailLimit = tailChars;
        int targetIndex = stepNum - 1;
        return workflowRunRepository.findByIdWithSteps(runId).map(run ->
                run.getSteps().stream()
                        .filter(s -> s.getStepIndex() == targetIndex)
                        .findFirst()
                        .map(s -> {
                            var sb = new StringBuilder();
                            sb.append("**Step ").append(stepNum).append("** (").append(s.getStatus()).append(")");
                            if (s.getDurationMs() != null) sb.append(" — ").append(s.getDurationMs() / 1000).append("s");
                            sb.append("\n");
                            String out = s.getOutput();
                            if (out != null && !out.isBlank()) {
                                String rendered;
                                if (windowOffset != null) {
                                    rendered = TruncationUtil.window(out, windowOffset, 8000);
                                } else if (tailLimit != null) {
                                    rendered = TruncationUtil.tail(out, Math.min(tailLimit, 8000));
                                } else {
                                    // Tail-biased default: errors sit at the end of the output.
                                    rendered = TruncationUtil.smart(out, 2000, 6000);
                                }
                                sb.append(rendered).append("\n");
                            } else {
                                sb.append("(No output)\n");
                            }
                            return sb.toString();
                        })
                        .orElse("Step " + stepNum + " not found in run " + runId.substring(0, Math.min(8, runId.length())) + "…\n")
        ).orElse("Run " + runId + " not found.\n");
    }
}
