package com.automation.service.assistant;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Confirmed assistant tools that touch workflows and runs ({@code create_workflow},
 * {@code update_workflow}, {@code delete_workflow}, {@code start_workflow}, {@code cancel_run}).
 * Each method takes the sanitized JSON payload and returns the user-facing result line
 * ({@code ✓ …} on success, {@code ⚠ …} otherwise) — never throws.
 *
 * <p>Extracted from {@code AssistantToolExecutor} (Audit-TODO 9f166f55). Guardrails, audit and
 * dispatch stay in the executor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantWorkflowTools {

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final RunControlService runControlService;
    private final ObjectMapper objectMapper;
    private final GeneratedWorkflowCheck buildCheck;

    public String createWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String projectId = (String) params.get("projectId");
            String name = (String) params.get("name");
            if (projectId == null || projectId.isBlank() || name == null || name.isBlank())
                return "⚠ create_workflow: projectId and name are required fields.";

            List<BlockDefinition> blocks = params.containsKey("blocks")
                    ? objectMapper.convertValue(params.get("blocks"), new TypeReference<>() {})
                    : List.of();

            Map<String, Object> defaultContext = params.containsKey("defaultContext")
                    ? objectMapper.convertValue(params.get("defaultContext"), new TypeReference<Map<String, Object>>() {})
                    : null;
            Map<String, Object> metadata = params.containsKey("metadata")
                    ? objectMapper.convertValue(params.get("metadata"), new TypeReference<Map<String, Object>>() {})
                    : null;

            var request = new CreateWorkflowRequest(
                    projectId, name,
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", "⚡"),
                    blocks,
                    null, // activatedSkillIds
                    (String) params.getOrDefault("instructions", null),
                    (String) params.getOrDefault("model", null),
                    null, // provider
                    null, // templateId
                    metadata, defaultContext,
                    null  // version
            );
            Workflow created = workflowService.create(request, ChangeSource.ASSISTANT);
            return "✓ Workflow created: \"" + created.getName() + "\" (ID: " + created.getId() + ")"
                    + buildCheckNote(created);
        } catch (Exception e) {
            log.warn("Assistant create_workflow tool failed. Payload: {}", json, e);
            return "⚠ Could not create workflow: " + e.getMessage();
        }
    }

    public String updateWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ update_workflow: workflowId is a required field.";

            List<BlockDefinition> blocks = params.containsKey("blocks")
                    ? objectMapper.convertValue(params.get("blocks"), new TypeReference<>() {})
                    : null;
            Map<String, Object> defaultContext = params.containsKey("defaultContext")
                    ? objectMapper.convertValue(params.get("defaultContext"), new TypeReference<Map<String, Object>>() {})
                    : null;
            Map<String, Object> metadata = params.containsKey("metadata")
                    ? objectMapper.convertValue(params.get("metadata"), new TypeReference<Map<String, Object>>() {})
                    : null;

            var request = new UpdateWorkflowRequest(
                    (String) params.getOrDefault("name", null),
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", null),
                    blocks,
                    (String) params.getOrDefault("instructions", null),
                    (String) params.getOrDefault("model", null),
                    null, // provider
                    metadata, defaultContext,
                    null, null, null, null, // changeMessage, tags, category, effort
                    version(params)
            );
            Workflow updated = workflowService.update(workflowId, request, ChangeSource.ASSISTANT);
            return "✓ Workflow updated: \"" + updated.getName() + "\" (ID: " + workflowId + ")"
                    + buildCheckNote(updated);
        } catch (Exception e) {
            log.warn("Assistant update_workflow tool failed. Payload: {}", json, e);
            return "⚠ Could not update workflow: " + e.getMessage();
        }
    }

    /**
     * Build check of the saved definition (TODO 326d5987): findings from MEDIUM up, HIGH first, appended to
     * the result line so the chat can fix them with {@code update_workflow}. Empty without such findings —
     * the line then stays exactly as before. No extra model call; a failing check never fails the tool.
     */
    private String buildCheckNote(Workflow workflow) {
        try {
            var findings = buildCheck.findings(workflow);
            if (findings.isEmpty()) {
                return "";
            }
            return "\n\nBaufehler-Prüfung: " + findings.size() + " Befund(e) ab MEDIUM\n"
                    + GeneratedWorkflowCheck.format(findings)
                    + "\nMit update_workflow lassen sie sich beheben.";
        } catch (Exception e) {
            log.warn("Build check after assistant tool failed for workflow {}: {}", workflow.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * The version label, like PUT: absent keeps it, blank is refused with the reason (TODO 5e2e73a5). A model may
     * write it as a number ({@code 2}), which counts as its text.
     */
    private static String version(Map<String, Object> params) {
        Object version = params.get("version");
        return version != null ? String.valueOf(version) : null;
    }

    public String deleteWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ delete_workflow: workflowId is a required field.";

            Workflow workflow = workflowService.findById(workflowId);
            String name = workflow.getName();
            workflowService.delete(workflowId);
            return "✓ Workflow deleted: \"" + name + "\" (ID: " + workflowId + ")";
        } catch (Exception e) {
            log.warn("Assistant delete_workflow tool failed. Payload: {}", json, e);
            return "⚠ Could not delete workflow: " + e.getMessage();
        }
    }

    public String startWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ start_workflow: workflowId is a required field.";

            Workflow workflow = workflowService.findById(workflowId);
            Map<String, Object> context = params.containsKey("context")
                    ? objectMapper.convertValue(params.get("context"), new TypeReference<Map<String, Object>>() {})
                    : Map.of();

            WorkflowRun run = workflowExecutor.execute(workflow, context, TriggerSource.ASSISTANT);
            String shortId = run.getId().substring(0, Math.min(8, run.getId().length()));
            return "✓ Workflow started: \"" + workflow.getName() + "\" (Run ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant start_workflow tool failed. Payload: {}", json, e);
            return "⚠ Could not start workflow: " + e.getMessage();
        }
    }

    public String cancelRun(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String runId = (String) params.get("runId");
            if (runId == null || runId.isBlank())
                return "⚠ cancel_run: runId is a required field.";

            runControlService.cancelExecution(runId);
            String shortId = runId.substring(0, Math.min(8, runId.length()));
            return "✓ Run cancelled (ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant cancel_run tool failed. Payload: {}", json, e);
            return "⚠ Could not cancel run: " + e.getMessage();
        }
    }
}
