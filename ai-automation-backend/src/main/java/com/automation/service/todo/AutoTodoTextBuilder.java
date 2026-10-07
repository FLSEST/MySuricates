package com.automation.service.todo;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.StepResultRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the text parts of auto-todos: description (gate description or the run's report step),
 * granular-item description, source footer, the track-mode update entry and the tags JSON.
 *
 * <p>Extracted from {@code AutoTodoService} (Audit-TODO 9b4845ab). Reads step outputs, writes
 * nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTodoTextBuilder {

    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    /** Cap of a track-mode description, so repeated updates cannot grow it without bound. */
    static final int MAX_TRACK_DESCRIPTION_LENGTH = 8000;
    private static final DateTimeFormatter TRACK_UPDATE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StepResultRepository stepResultRepository;

    String buildGranularDescription(String itemDescription, WorkflowRunCompletedEvent event) {
        StringBuilder sb = new StringBuilder();
        if (itemDescription != null && !itemDescription.isBlank()) {
            sb.append(itemDescription);
        }
        sb.append("\n\n---\n").append(buildSourceFooter(event));
        return sb.toString();
    }

    String buildDescription(WorkflowRunCompletedEvent event, GateResult gate) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(gate.status()).append(" — ").append(gate.category()).append("\n\n");

        // The workflow's explicit gate_description is authoritative — the step heuristic
        // below only guesses by position and can pick a misleading step (e.g. a green
        // frontend build as "evidence" for a backend-test-failure).
        if (gate.description() != null && !gate.description().isBlank()) {
            sb.append(gate.description());
        } else {
            String reportContent = loadReportFromSteps(event.getRunId());
            if (reportContent != null && !reportContent.isBlank()) {
                sb.append(reportContent);
            }
        }

        sb.append("\n\n---\n").append(buildSourceFooter(event));
        return sb.toString();
    }

    /**
     * Builds a markdown footer that links the auto-todo back to its source run/workflow.
     * Uses relative URLs so the same TODO renders correctly on every instance (Dev/Stable/Test).
     */
    String buildSourceFooter(WorkflowRunCompletedEvent event) {
        String wfId = event.getWorkflowId();
        String runId = event.getRunId();
        String name = event.getWorkflowName();
        if (wfId != null && runId != null) {
            return "*Source: [" + name + "](/workflows/" + wfId + ")"
                    + " · [Run →](/workflows/" + wfId + "/run/" + runId + ")*";
        }
        return "*Workflow: " + name + " | Run: `" + runId + "`*";
    }

    /**
     * Loads the most relevant report from the workflow run's step outputs.
     * Heuristic: second-to-last completed step with substantial output (the analysis/report block).
     * Skips the last step (typically the verdict shell block) and the first step (data collection).
     */
    String loadReportFromSteps(String runId) {
        try {
            List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId);
            if (steps.size() < 2) return null;

            // Walk backwards from second-to-last, find the first step with substantial output
            for (int i = steps.size() - 2; i >= 1; i--) {
                StepResult step = steps.get(i);
                if (step.getStatus() != StepStatus.COMPLETED) continue;
                String output = step.getOutput();
                if (output != null && output.length() > 200) {
                    return output.length() > MAX_DESCRIPTION_LENGTH
                            ? output.substring(0, MAX_DESCRIPTION_LENGTH) + "\n\n[...gekürzt]"
                            : output;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load step outputs for run {}: {}", runId, e.getMessage());
        }
        return null;
    }

    /**
     * Track mode: appends an update entry (timestamp, run id, the run's report or else the gate
     * description) to the current description. When the result exceeds
     * {@link #MAX_TRACK_DESCRIPTION_LENGTH}, the oldest part is dropped and the text restarts at the
     * next update separator behind a {@code ...} marker.
     */
    String appendTrackUpdate(String currentDescription, WorkflowRunCompletedEvent event, GateResult gate) {
        return appendTrackUpdate(currentDescription, event, gate, LocalDateTime.now());
    }

    String appendTrackUpdate(String currentDescription, WorkflowRunCompletedEvent event, GateResult gate,
                             LocalDateTime timestamp) {
        String newReport = loadReportFromSteps(event.getRunId());
        String updateEntry = String.format("\n\n---\n### Update %s\n*Run: `%s`*\n\n%s",
                timestamp.format(TRACK_UPDATE_TIMESTAMP),
                event.getRunId(),
                newReport != null ? newReport : gate.description());
        String merged = (currentDescription != null ? currentDescription : "") + updateEntry;
        if (merged.length() > MAX_TRACK_DESCRIPTION_LENGTH) {
            merged = merged.substring(merged.length() - MAX_TRACK_DESCRIPTION_LENGTH);
            merged = "...\n" + merged.substring(merged.indexOf("\n---\n") + 1);
        }
        return merged;
    }

    String buildTagsJson(GateResult gate) {
        List<String> tags = new ArrayList<>();
        tags.add(AutoTodoService.TAG_AUTO_WORKFLOW);
        tags.add("gate:" + gate.status().name().toLowerCase(Locale.ROOT));
        tags.add("category:" + gate.category());
        return TodoMapper.listToJson(tags);
    }
}
