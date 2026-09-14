package com.automation.service.assistant;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
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
    private final ObjectMapper objectMapper;

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
            Workflow created = workflowService.create(request);
            return "✓ Workflow created: \"" + created.getName() + "\" (ID: " + created.getId() + ")";
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
                    metadata, defaultContext
            );
            Workflow updated = workflowService.update(workflowId, request);
            return "✓ Workflow updated: \"" + updated.getName() + "\" (ID: " + workflowId + ")";
        } catch (Exception e) {
            log.warn("Assistant update_workflow tool failed. Payload: {}", json, e);
            return "⚠ Could not update workflow: " + e.getMessage();
        }
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

            workflowExecutor.cancelExecution(runId);
            String shortId = runId.substring(0, Math.min(8, runId.length()));
            return "✓ Run cancelled (ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant cancel_run tool failed. Payload: {}", json, e);
            return "⚠ Could not cancel run: " + e.getMessage();
        }
    }
}
