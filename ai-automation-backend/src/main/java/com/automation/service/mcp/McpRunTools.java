package com.automation.service.mcp;

import com.automation.dto.response.StepResultResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ApprovalRequest;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.execution.PendingInputService;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Running workflows and answering them: start (optionally waiting a little), status, list, cancel, the
 * open approvals and human inputs, and the answer to one.
 */
@Component
@RequiredArgsConstructor
public class McpRunTools implements McpToolset {

    static final int MAX_WAIT_SECONDS = 120;
    private static final int STEP_OUTPUT_PREVIEW = 2000;
    private static final int STEP_OUTPUT_FULL = 50_000;
    private static final int STEP_INPUT_FULL = 20_000;
    private static final Set<RunStatus> FINISHED = EnumSet.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final ExecutionQueryService executionQueryService;
    private final RunControlService runControlService;
    private final PendingInputService pendingInputService;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpTool> tools() {
        return List.of(
                // Destructive: a workflow can run shell commands and AI agents with write access in its project.
                McpTool.writing("run_workflow", "Run workflow",
                        "Starts a workflow run. It runs in the background on the instance; with 'waitSeconds' "
                                + "the call waits up to that long for the run to finish or to stop at a question "
                                + "(approval / human input — answer it with answer_input). Returns the run as "
                                + "get_run does. A refused start (e.g. a sealed workflow that was changed) returns "
                                + "the reason and creates no run.",
                        McpSchema.object()
                                .string("workflowId", "Workflow id", true)
                                .objectProperty("context", "Variables for this run, merged over the workflow's "
                                        + "default context (e.g. {\"topic\": \"...\"})", false)
                                .integer("waitSeconds", "Seconds to wait for the end or a question (default 20)",
                                        0, MAX_WAIT_SECONDS)
                                .build(),
                        true, this::runWorkflow),
                McpTool.reading("get_run", "Get run",
                        "Returns a run: status, error, the open question if it waits for an answer, and every "
                                + "step with a preview of its output. With 'step' the full output (and input) of "
                                + "that one step.",
                        McpSchema.object()
                                .string("runId", "Run id", true)
                                .integer("step", "Step index for the full output of one step", 0, 10_000)
                                .build(),
                        this::getRun),
                McpTool.reading("list_runs", "List runs",
                        "Lists runs, newest first, without their steps.",
                        McpSchema.object()
                                .string("workflowId", "Only runs of this workflow", false)
                                .enumeration("status", "Only runs with this status",
                                        List.of("RUNNING", "PAUSED", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED",
                                                "CANCELLED", "INTERRUPTED"), false)
                                .integer("limit", "Maximum number of entries (default 20)", 1, 100)
                                .build(),
                        this::listRuns),
                McpTool.writing("cancel_run", "Cancel run", "Cancels a running run.",
                        McpSchema.object().string("runId", "Run id", true).build(),
                        true, args -> {
                            runControlService.cancelExecution(args.string("runId"));
                            return McpToolResult.ok(objectMapper.createObjectNode().put("cancelled", true));
                        }),
                McpTool.reading("list_pending_inputs", "List open questions",
                        "Lists every run that waits for a person: approvals and human input, oldest first, "
                                + "with question, options and form fields.",
                        McpSchema.none(),
                        args -> McpToolResult.ok("pending", objectMapper.valueToTree(pendingInputService.listPendingInputs()))),
                McpTool.writing("answer_input", "Answer question",
                        "Answers the question a run waits for. Approval: one of its 'options' (accept) or "
                                + "'rejectOptions' (reject) — anything else is refused. Human input: the value; "
                                + "a form with several fields takes a JSON object as string, e.g. "
                                + "'{\"city\":\"Bern\",\"nights\":\"3\"}'. Confirm the answer with the user "
                                + "first — it is their decision.",
                        McpSchema.object()
                                .string("runId", "Run id", true)
                                .string("answer", "The answer", true)
                                .build(),
                        false, args -> {
                            runControlService.resolveApproval(args.string("runId"), args.string("answer"));
                            return McpToolResult.ok(objectMapper.createObjectNode().put("answered", true));
                        }));
    }

    private McpToolResult runWorkflow(McpArgs args) {
        Workflow workflow = workflowService.findByIdEager(args.string("workflowId"));
        ObjectNode context = args.optionalObject("context");
        int waitSeconds = args.integer("waitSeconds", 20, 0, MAX_WAIT_SECONDS);
        Map<String, Object> variables = context == null ? Map.of()
                : objectMapper.convertValue(context, new TypeReference<Map<String, Object>>() {});
        WorkflowRun run = workflowExecutor.execute(workflow, variables, TriggerSource.MCP);
        waitForEndOrQuestion(run.getId(), waitSeconds);
        return McpToolResult.ok(runView(executionQueryService.getExecutionResponse(run.getId())));
    }

    /** Polls the status (cheap, no steps) once a second. */
    private void waitForEndOrQuestion(String runId, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (FINISHED.contains(executionQueryService.statusOf(runId))
                    || runControlService.getPendingApproval(runId) != null) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private McpToolResult getRun(McpArgs args) {
        String runId = args.string("runId");
        WorkflowRunResponse run = executionQueryService.getExecutionResponse(runId);
        if (!args.has("step")) {
            return McpToolResult.ok(runView(run));
        }
        int index = args.integer("step", 0, 0, 10_000);
        StepResultResponse step = run.steps() == null ? null : run.steps().stream()
                .filter(s -> s.stepIndex() == index).findFirst().orElse(null);
        if (step == null) {
            return McpToolResult.error("Run " + runId + " has no step " + index + ".");
        }
        ObjectNode data = stepView(step, STEP_OUTPUT_FULL);
        data.put("input", cut(step.input(), STEP_INPUT_FULL));
        return McpToolResult.ok(data);
    }

    private McpToolResult listRuns(McpArgs args) {
        String status = args.optionalString("status");
        int limit = args.integer("limit", 20, 1, 100);
        var page = executionQueryService.listExecutionResponses(0, limit, status,
                args.optionalString("workflowId"), null, null);
        ArrayNode list = objectMapper.createArrayNode();
        for (WorkflowRunListItemResponse r : page.content()) {
            ObjectNode node = list.addObject();
            node.put("id", r.id());
            node.put("workflowId", r.workflowId());
            node.put("workflowName", r.workflowName());
            node.put("status", r.status() == null ? null : r.status().name());
            node.put("triggerSource", r.triggerSource() == null ? null : r.triggerSource().name());
            node.put("startedAt", r.startedAt() == null ? null : r.startedAt().toString());
            node.put("durationMs", r.durationMs());
            node.put("steps", r.stepCount());
            node.put("lastStepPreview", McpCatalogTools.preview(r.lastStepPreview()));
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("total", page.totalElements());
        data.set("runs", list);
        return McpToolResult.ok(data);
    }

    /** The run without its inputs, with an output preview per step and the open question. */
    ObjectNode runView(WorkflowRunResponse run) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", run.id());
        data.put("workflowId", run.workflowId());
        data.put("workflowName", run.workflowName());
        data.put("status", run.status() == null ? null : run.status().name());
        data.put("finished", run.status() != null && FINISHED.contains(run.status()));
        data.put("triggerSource", run.triggerSource() == null ? null : run.triggerSource().name());
        data.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
        data.put("completedAt", run.completedAt() == null ? null : run.completedAt().toString());
        data.put("durationMs", run.durationMs());
        if (run.error() != null && !run.error().isEmpty()) {
            data.set("error", objectMapper.valueToTree(run.error()));
        }
        ApprovalRequest question = runControlService.getPendingApproval(run.id());
        if (question != null) {
            data.set("waitingFor", questionView(question));
        }
        ArrayNode steps = data.putArray("steps");
        if (run.steps() != null) {
            run.steps().forEach(s -> steps.add(stepView(s, STEP_OUTPUT_PREVIEW)));
        }
        return data;
    }

    private ObjectNode questionView(ApprovalRequest q) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", q.getType());
        node.put("question", q.getQuestion());
        node.put("step", q.getStepIndex());
        node.set("options", objectMapper.valueToTree(q.getOptions()));
        node.set("rejectOptions", objectMapper.valueToTree(q.getRejectOptions()));
        if (q.getFields() != null && !q.getFields().isEmpty()) {
            node.set("fields", objectMapper.valueToTree(q.getFields()));
        }
        node.put("hint", "Ask the user, then answer with answer_input.");
        return node;
    }

    private ObjectNode stepView(StepResultResponse s, int outputLimit) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("index", s.stepIndex());
        node.put("name", s.blockName());
        node.put("type", s.blockType());
        node.put("status", s.status() == null ? null : s.status().name());
        node.put("durationMs", s.durationMs());
        node.put("output", cut(s.output(), outputLimit));
        return node;
    }

    static String cut(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        String more = "\n… [" + (text.length() - limit) + " more characters";
        return text.substring(0, limit) + more
                + (limit == STEP_OUTPUT_PREVIEW ? " — get_run with 'step' returns the full output]" : "]");
    }
}
