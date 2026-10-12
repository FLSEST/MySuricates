package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.automation.service.assistant.format.AssistantFormatUtils.extractError;
import static com.automation.service.assistant.format.AssistantFormatUtils.truncate;

/**
 * Renders the "Current User Context" block of the assistant system prompt: page header, the
 * currently displayed run (with failed-step evidence), the active workflow, wish details and
 * the active project incl. file listing.
 *
 * <p>Extracted from {@code AssistantContextBuilder} (Audit-TODO cb80664c). Owns no section
 * selection — which platform sections precede this block stays in the builder.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PageContextRenderer {

    private final WorkflowRunRepository workflowRunRepository;
    private final ProjectRepository projectRepository;
    private final WishRepository wishRepository;
    private final WorkflowRepository workflowRepository;
    private final ProjectFileListing projectFileListing;

    static String resolveRunId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.runId() != null) return ctx.runId();
        if ("run".equals(ctx.page()) || "runs".equals(ctx.page())) return ctx.entityId();
        return null;
    }

    static String resolveWorkflowId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.workflowId() != null) return ctx.workflowId();
        if ("workflow".equals(ctx.page()) || "workflows".equals(ctx.page())) return ctx.entityId();
        return null;
    }

    /** Header, displayed run and workflow context for the run-focused prompt (RUNS page with a run id). */
    public void appendRunFocusedContext(StringBuilder sb, AssistantChatRequest.PageContext ctx) {
        sb.append("## Current User Context\n");
        if (ctx.page() != null) sb.append("Page: /").append(ctx.page()).append("\n");
        if (ctx.pathname() != null) sb.append("Path: ").append(ctx.pathname()).append("\n");
        if (ctx.workflowId() != null) sb.append("Workflow ID: ").append(ctx.workflowId()).append("\n");
        if (ctx.runId() != null) sb.append("Run ID: ").append(ctx.runId()).append("\n");
        if (ctx.entityId() != null) sb.append("Displayed element: ID ").append(ctx.entityId()).append("\n");

        String runId = resolveRunId(ctx);
        if (runId != null) {
            try {
                workflowRunRepository.findByIdWithSteps(runId).ifPresent(run -> {
                    sb.append("### Currently displayed run\n");
                    appendRunSummary(sb, run, resolveRunWorkflow(ctx, run));
                });
            } catch (Exception e) { log.debug("Page context: displayed run skipped", e); }
        }

        String workflowId = resolveWorkflowId(ctx);
        appendWorkflowContext(sb, workflowId, false);
        sb.append("\n");
    }

    /** Full page context for every other page (run if any, workflow, wish, project + files). */
    public void appendPageContext(StringBuilder sb, AssistantChatRequest.PageContext ctx) {
        sb.append("## Current User Context\n");
        if (ctx.page() != null) sb.append("Page: /").append(ctx.page()).append("\n");
        if (ctx.pathname() != null) sb.append("Path: ").append(ctx.pathname()).append("\n");
        if (ctx.workflowId() != null) sb.append("Workflow ID: ").append(ctx.workflowId()).append("\n");
        if (ctx.runId() != null) sb.append("Run ID: ").append(ctx.runId()).append("\n");
        if (ctx.entityId() != null) {
            sb.append("Displayed element: ID ").append(ctx.entityId()).append("\n");
        }

        String runId = resolveRunId(ctx);
        if (runId != null) {
            try {
                workflowRunRepository.findByIdWithSteps(runId).ifPresent(run -> {
                    sb.append("### Aktuell angezeigter Run\n");
                    appendRunSummary(sb, run, resolveRunWorkflow(ctx, run));
                });
            } catch (Exception e) { log.debug("Page context: displayed run skipped", e); }
        }

        String workflowId = resolveWorkflowId(ctx);
        appendWorkflowContext(sb, workflowId, true);

        if ("wishes".equals(ctx.page()) && ctx.entityId() != null) {
            try {
                wishRepository.findById(ctx.entityId()).ifPresent(w ->
                        sb.append("Wish details: [").append(w.getStatus()).append("] ")
                                .append(truncate(w.getOriginalText(), 100))
                                .append(", iterations: ").append(w.getIterationCount())
                                .append("/").append(w.getMaxIterations()).append("\n")
                );
            } catch (Exception e) { log.debug("Page context: wish skipped", e); }
        }
        if (ctx.projectId() != null) {
            try {
                projectRepository.findById(ctx.projectId()).ifPresent(p -> {
                    sb.append("Active project: ").append(p.getName())
                            .append(" (ID: ").append(p.getId()).append(")\n");
                    projectFileListing.append(sb, p.getPath(), p.getId());
                });
            } catch (Exception e) { log.debug("Page context: project skipped", e); }
        } else if (workflowId != null) {
            try {
                workflowRepository.findByIdWithSkills(workflowId)
                        .map(Workflow::getProject)
                        .filter(project -> project != null)
                        .ifPresent(project -> {
                            sb.append("Active project: ").append(project.getName())
                                    .append(" (ID: ").append(project.getId()).append(")\n");
                            projectFileListing.append(sb, project.getPath(), project.getId());
                        });
            } catch (Exception e) { log.debug("Page context: project skipped", e); }
        }
        sb.append("\n");
    }

    /**
     * Loads the run's workflow via the repository instead of navigating the lazy
     * {@code run.workflow} proxy: OSIV is off and the assistant path has no open
     * session, so proxy navigation throws LazyInitializationException (silently
     * swallowed by callers — the summary then vanished from the prompt). A fetch
     * join is no option either: Workflow's @SQLRestriction turns it into a
     * FetchNotFoundException for soft-deleted workflows. Returns null when the
     * workflow is soft-deleted or unresolvable.
     */
    private Workflow resolveRunWorkflow(AssistantChatRequest.PageContext ctx, WorkflowRun run) {
        String wfId = resolveWorkflowId(ctx);
        if (wfId == null) {
            try {
                wfId = run.getWorkflow() != null ? run.getWorkflow().getId() : null;
            } catch (Exception e) { log.debug("Page context: workflow of the run skipped", e); }
        }
        if (wfId == null) return null;
        try {
            return workflowRepository.findById(wfId).orElse(null);
        } catch (Exception e) {
            log.debug("Page context: workflow {} not loadable", wfId, e);
            return null;
        }
    }

    private void appendRunSummary(StringBuilder sb, WorkflowRun run, Workflow workflow) {
        String name = run.getWorkflowName();
        if ((name == null || name.isBlank()) && workflow != null) {
            name = workflow.getName();
        }
        sb.append("Workflow: ").append(name != null ? name : "(unknown)");
        if (workflow == null && name != null) {
            sb.append(" [deleted]");
        }
        sb.append("\n");
        if (workflow != null) {
            sb.append("Workflow ID: ").append(workflow.getId()).append("\n");
            if (workflow.getProject() != null) {
                sb.append("Workflow project: ").append(workflow.getProject().getName())
                        .append(" (ID: ").append(workflow.getProject().getId()).append(")\n");
            }
        }
        sb.append("Status: ").append(run.getStatus());
        if (run.getDurationMs() != null) {
            sb.append(" (").append(run.getDurationMs() / 1000).append("s)");
        }
        sb.append("\n");
        String runErr = extractError(run);
        if (runErr != null) {
            sb.append("Run error: ").append(TruncationUtil.smart(runErr, 200, 400)).append("\n");
        }

        List<StepResult> steps = run.getSteps();
        if (!steps.isEmpty()) {
            sb.append("Steps: ").append(steps.size());
            String counts = summarizeStepStatuses(steps);
            if (!counts.isBlank()) {
                sb.append(" (").append(counts).append(")");
            }
            sb.append("\n");
            steps.stream()
                    .filter(step -> step.getStatus() == StepStatus.FAILED)
                    .findFirst()
                    .ifPresent(step -> sb.append("First failed step: ")
                            .append(step.getStepIndex() + 1).append("\n"));
        }
        if (run.getStatus() == RunStatus.FAILED) {
            appendFailedStepEvidence(sb, run, workflow);
        }
    }

    /**
     * Pre-loads the diagnosis evidence for a FAILED run directly into the system
     * prompt: block config and a tail-biased slice of the failed step's output.
     * One-shot diagnosis without a fetch round-trip; costs tokens only for
     * failed runs on run pages.
     */
    private void appendFailedStepEvidence(StringBuilder sb, WorkflowRun run, Workflow workflow) {
        List<StepResult> failed = run.getSteps().stream()
                .filter(s -> s.getStatus() == StepStatus.FAILED)
                .toList();
        if (failed.isEmpty()) return;
        StepResult step = failed.get(0);
        String blockType = step.getMetadata() != null
                ? String.valueOf(step.getMetadata().getOrDefault("blockType", "?"))
                : "?";
        sb.append("Failed step ").append(step.getStepIndex() + 1)
                .append(" (").append(blockType).append(")\n");
        appendFailedBlockConfig(sb, workflow, step, blockType);
        String output = step.getOutput();
        sb.append("Failed step output:\n")
                .append(output == null || output.isBlank()
                        ? "(no output captured)"
                        : TruncationUtil.smart(output, 500, 4000))
                .append("\n");
        sb.append("Full output via <fetch type=\"step_output\" id=\"").append(run.getId())
                .append("/").append(step.getStepIndex() + 1)
                .append("\"/> (page with ?offset=...)\n");
        if (failed.size() > 1) {
            sb.append("Other failed steps: ")
                    .append(failed.stream().skip(1)
                            .map(s -> String.valueOf(s.getStepIndex() + 1))
                            .collect(Collectors.joining(", ")))
                    .append("\n");
        }
    }

    private void appendFailedBlockConfig(StringBuilder sb, Workflow workflow, StepResult step, String blockType) {
        if (workflow == null || workflow.getBlocks() == null) return;
        int idx = step.getStepIndex();
        if (idx < 0 || idx >= workflow.getBlocks().size()) return;
        var block = workflow.getBlocks().get(idx);
        if (block == null || block.getType() == null) return;
        sb.append("Block type: ").append(block.getType());
        if (block.getName() != null && !block.getName().isBlank()) {
            sb.append(" — ").append(block.getName());
        }
        sb.append("\n");
        // Workflow may have been edited since the run — only show config when the
        // block type still matches what actually executed (step metadata snapshot).
        if (!block.getType().equals(blockType) || block.getConfig() == null) return;
        for (String key : List.of("command", "prompt", "model", "timeout_seconds")) {
            Object value = block.getConfig().get(key);
            if (value != null) {
                sb.append("Block ").append(key).append(": ")
                        .append(truncate(String.valueOf(value), 500)).append("\n");
            }
        }
    }

    private String summarizeStepStatuses(List<StepResult> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return java.util.Arrays.stream(StepStatus.values())
                .map(status -> Map.entry(status, steps.stream().filter(step -> step.getStatus() == status).count()))
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void appendWorkflowContext(StringBuilder sb, String workflowId, boolean includeProjectFiles) {
        if (workflowId == null) return;
        try {
            workflowRepository.findByIdWithSkills(workflowId).ifPresent(workflow -> {
                sb.append("Active workflow: ").append(workflow.getName())
                        .append(" (ID: ").append(workflow.getId()).append(")\n");
                if (workflow.getProject() != null) {
                    sb.append("Workflow project: ").append(workflow.getProject().getName())
                            .append(" (ID: ").append(workflow.getProject().getId()).append(")\n");
                    if (includeProjectFiles) {
                        sb.append("Active project: ").append(workflow.getProject().getName())
                                .append(" (ID: ").append(workflow.getProject().getId()).append(")\n");
                        projectFileListing.append(sb, workflow.getProject().getPath(), workflow.getProject().getId());
                    }
                }
            });
        } catch (Exception e) { log.debug("Page context: workflow skipped", e); }
    }
}
