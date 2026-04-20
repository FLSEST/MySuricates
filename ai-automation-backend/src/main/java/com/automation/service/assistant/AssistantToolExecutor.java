package com.automation.service.assistant;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.WorkflowRun;
import com.automation.service.chain.ChainService;
import com.automation.service.todo.TodoService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and executes tool calls embedded in the assistant's response.
 * Tools are encoded as {@code <tool type="...">JSON</tool>} tags.
 *
 * <p><b>Hard-gate (2026-04-19)</b>: tool tags are <b>not</b> executed inline anymore.
 * {@link #extractTools(String)} returns a list of {@link PendingTool}s that the
 * {@link com.automation.controller.AssistantController} hands back to the frontend; execution
 * only happens after the user clicks "Bestätigen" via
 * {@code POST /api/v1/assistant/pending-tools/{id}/confirm}, which invokes
 * {@link #executeConfirmed(PendingTool)}. This prevents the LLM from mutating platform state
 * without explicit user approval, independent of prompt compliance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantToolExecutor {

    private static final Pattern TOOL_PATTERN =
            Pattern.compile("<tool type=\"([^\"]+)\">(.*?)</tool>", Pattern.DOTALL);
    private static final Set<String> MUTATING_TOOL_TYPES = Set.of(
            "create_todo",
            "update_todo_status",
            "create_workflow",
            "update_workflow",
            "delete_workflow",
            "start_workflow",
            "cancel_run",
            "create_chain"
    );

    private final TodoService todoService;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final ChainService chainService;
    private final ObjectMapper objectMapper;
    private final PendingToolRegistry pendingToolRegistry;

    /**
     * Result of {@link #extractTools(String)}: the cleaned response (with tool tags replaced
     * by placeholder text) plus the list of pending tool proposals to surface to the user.
     */
    public record ExtractionResult(String cleanedResponse, List<PendingTool> pendingTools) {}

    /**
     * Extracts all {@code <tool>} tags from the assistant response, registers them as pending
     * (to be confirmed by the user), and replaces them in the response text with a short
     * "wartet auf Bestätigung" placeholder. No tool is executed at this point.
     */
    public ExtractionResult extractTools(String response) {
        return extractTools(response, type -> true);
    }

    /**
     * Variant of {@link #extractTools(String)} that allows the caller to suppress specific tool
     * types. Suppressed tags are stripped from the response and never registered as pending.
     */
    public ExtractionResult extractTools(String response, Predicate<String> allowToolType) {
        if (response == null) return new ExtractionResult(null, List.of());
        Matcher m = TOOL_PATTERN.matcher(response);
        if (!m.find()) return new ExtractionResult(response, List.of());

        List<PendingTool> pending = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        do {
            String type = m.group(1);
            if (!allowToolType.test(type)) {
                m.appendReplacement(sb, "");
                continue;
            }
            String payload = sanitizeJson(m.group(2).trim());
            String description = describe(type, payload);
            PendingTool tool = pendingToolRegistry.register(type, description, payload);
            pending.add(tool);
            m.appendReplacement(sb,
                    Matcher.quoteReplacement("[Aktion wartet auf Bestätigung: " + description + "]"));
        } while (m.find());
        m.appendTail(sb);
        return new ExtractionResult(sb.toString(), pending);
    }

    public boolean isMutatingToolType(String type) {
        return MUTATING_TOOL_TYPES.contains(type);
    }

    /**
     * Executes a tool that the user has just confirmed. Called from the controller after
     * {@link PendingToolRegistry#take(String)} has returned the entry.
     */
    public ToolExecutionResult executeConfirmed(PendingTool tool) {
        String payload = sanitizeJson(tool.payloadJson());
        String rawResult = switch (tool.type()) {
            case "create_todo"        -> executeCreateTodo(payload);
            case "update_todo_status" -> executeUpdateTodoStatus(payload);
            case "create_workflow"    -> executeCreateWorkflow(payload);
            case "update_workflow"    -> executeUpdateWorkflow(payload);
            case "delete_workflow"    -> executeDeleteWorkflow(payload);
            case "start_workflow"     -> executeStartWorkflow(payload);
            case "cancel_run"         -> executeCancelRun(payload);
            case "create_chain"       -> executeCreateChain(payload);
            default -> "[Unbekanntes Tool: " + tool.type() + "]";
        };
        boolean success = rawResult != null && rawResult.startsWith("✓");
        return new ToolExecutionResult(tool.id(), tool.type(), success, rawResult);
    }

    /** Builds a short, user-facing description for a tool proposal. */
    String describe(String type, String payloadJson) {
        try {
            Map<String, Object> params = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            return switch (type) {
                case "create_todo" -> "TODO anlegen: \"" + params.getOrDefault("title", "?") + "\""
                        + " (Priorität: " + params.getOrDefault("priority", "MEDIUM") + ")";
                case "update_todo_status" -> "TODO-Status ändern → " + params.getOrDefault("status", "?");
                case "create_workflow" -> "Workflow erstellen: \"" + params.getOrDefault("name", "?") + "\"";
                case "update_workflow" -> "Workflow aktualisieren (ID: " + shorten(params.get("workflowId")) + ")";
                case "delete_workflow" -> "Workflow LÖSCHEN (ID: " + shorten(params.get("workflowId")) + ")";
                case "start_workflow" -> "Workflow starten (ID: " + shorten(params.get("workflowId")) + ")";
                case "cancel_run" -> "Run abbrechen (ID: " + shorten(params.get("runId")) + ")";
                case "create_chain" -> "Chain erstellen: \"" + params.getOrDefault("name", "?") + "\"";
                default -> type;
            };
        } catch (Exception e) {
            log.debug("describe failed for tool type={} — falling back to type label", type, e);
            return type;
        }
    }

    private static String shorten(Object id) {
        if (id == null) return "?";
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) + "…" : s;
    }

    /**
     * Normalizes fullwidth Unicode punctuation to ASCII equivalents.
     * Claude occasionally generates these characters in JSON payloads,
     * which Jackson's parser rejects.
     */
    private static String sanitizeJson(String json) {
        if (json == null || json.isEmpty()) return json;
        return json
                .replace('\uff0c', ',')   // ， → ,
                .replace('\uff1a', ':')   // ： → :
                .replace('\uff1b', ';')   // ； → ;
                .replace('\uff5b', '{')   // ｛ → {
                .replace('\uff5d', '}')   // ｝ → }
                .replace('\uff3b', '[')   // ［ → [
                .replace('\uff3d', ']')   // ］ → ]
                .replace('\uff02', '"')   // ＂ → "
                .replace('\u201c', '"')   // " → "
                .replace('\u201d', '"');  // " → "
    }

    private String executeCreateTodo(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String title = (String) params.get("title");
            if (title == null || title.isBlank()) return "⚠ Todo konnte nicht angelegt werden: Titel fehlt.";

            String description = (String) params.getOrDefault("description", null);
            String priorityStr = (String) params.getOrDefault("priority", "MEDIUM");
            Object tagsObj = params.get("tags");
            String projectId = (String) params.getOrDefault("projectId", null);

            Todo.Priority priority;
            try { priority = Todo.Priority.valueOf(priorityStr.toUpperCase()); }
            catch (Exception e) { priority = Todo.Priority.MEDIUM; }

            List<String> tags = tagsObj instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();

            var request = new CreateTodoRequest(
                    title, description, Todo.Status.OPEN, priority, tags,
                    projectId, null, Todo.Source.AI, "assistant", null, null, null, null, null
            );
            Todo created = todoService.create(request);
            String tagStr = tags.isEmpty() ? "" : ", Tags: " + String.join(", ", tags);
            String shortId = created.getId().substring(0, Math.min(8, created.getId().length()));
            return "✓ Todo angelegt: \"" + created.getTitle() + "\" (Priorität: " + priority + tagStr + ", ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant create_todo tool failed. Payload: {}", json, e);
            return "⚠ Todo konnte nicht angelegt werden: " + e.getMessage();
        }
    }

    private String executeUpdateTodoStatus(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String id = (String) params.get("id");
            String statusStr = (String) params.get("status");
            if (id == null || statusStr == null) return "⚠ update_todo_status: id und status sind Pflichtfelder.";

            Todo.Status status;
            try { status = Todo.Status.valueOf(statusStr.toUpperCase()); }
            catch (Exception e) { return "⚠ Ungültiger Status: " + statusStr + ". Erlaubt: OPEN, IN_PROGRESS, DONE, BACKLOG"; }

            todoService.updateStatus(id, new UpdateTodoStatusRequest(status, null));
            return "✓ Todo-Status aktualisiert: " + status + " (ID: " + id.substring(0, Math.min(8, id.length())) + "…)";
        } catch (Exception e) {
            log.warn("Assistant update_todo_status tool failed. Payload: {}", json, e);
            return "⚠ Status konnte nicht aktualisiert werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCreateWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String projectId = (String) params.get("projectId");
            String name = (String) params.get("name");
            if (projectId == null || projectId.isBlank() || name == null || name.isBlank())
                return "⚠ create_workflow: projectId und name sind Pflichtfelder.";

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
            return "✓ Workflow erstellt: \"" + created.getName() + "\" (ID: " + created.getId() + ")";
        } catch (Exception e) {
            log.warn("Assistant create_workflow tool failed. Payload: {}", json, e);
            return "⚠ Workflow konnte nicht erstellt werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeUpdateWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ update_workflow: workflowId ist Pflichtfeld.";

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
            return "✓ Workflow aktualisiert: \"" + updated.getName() + "\" (ID: " + workflowId + ")";
        } catch (Exception e) {
            log.warn("Assistant update_workflow tool failed. Payload: {}", json, e);
            return "⚠ Workflow konnte nicht aktualisiert werden: " + e.getMessage();
        }
    }

    private String executeDeleteWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ delete_workflow: workflowId ist Pflichtfeld.";

            Workflow workflow = workflowService.findById(workflowId);
            String name = workflow.getName();
            workflowService.delete(workflowId);
            return "✓ Workflow gelöscht: \"" + name + "\" (ID: " + workflowId + ")";
        } catch (Exception e) {
            log.warn("Assistant delete_workflow tool failed. Payload: {}", json, e);
            return "⚠ Workflow konnte nicht gelöscht werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeStartWorkflow(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String workflowId = (String) params.get("workflowId");
            if (workflowId == null || workflowId.isBlank())
                return "⚠ start_workflow: workflowId ist Pflichtfeld.";

            Workflow workflow = workflowService.findById(workflowId);
            Map<String, Object> context = params.containsKey("context")
                    ? objectMapper.convertValue(params.get("context"), new TypeReference<Map<String, Object>>() {})
                    : Map.of();

            WorkflowRun run = workflowExecutor.execute(workflow, context);
            String shortId = run.getId().substring(0, Math.min(8, run.getId().length()));
            return "✓ Workflow gestartet: \"" + workflow.getName() + "\" (Run-ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant start_workflow tool failed. Payload: {}", json, e);
            return "⚠ Workflow konnte nicht gestartet werden: " + e.getMessage();
        }
    }

    private String executeCancelRun(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String runId = (String) params.get("runId");
            if (runId == null || runId.isBlank())
                return "⚠ cancel_run: runId ist Pflichtfeld.";

            workflowExecutor.cancelExecution(runId);
            String shortId = runId.substring(0, Math.min(8, runId.length()));
            return "✓ Run abgebrochen (ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant cancel_run tool failed. Payload: {}", json, e);
            return "⚠ Run konnte nicht abgebrochen werden: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String executeCreateChain(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String name = (String) params.get("name");
            if (name == null || name.isBlank())
                return "⚠ create_chain: name ist Pflichtfeld.";

            List<Map<String, Object>> rawEntries = params.containsKey("entries")
                    ? objectMapper.convertValue(params.get("entries"), new TypeReference<>() {})
                    : List.of();
            if (rawEntries.isEmpty())
                return "⚠ create_chain: entries darf nicht leer sein.";

            List<ChainEntryRequest> entries = rawEntries.stream().map(e -> new ChainEntryRequest(
                    (String) e.get("workflowId"),
                    (String) e.getOrDefault("label", null),
                    e.containsKey("sortOrder") ? ((Number) e.get("sortOrder")).intValue() : 0,
                    e.containsKey("stopOnFailure") ? (Boolean) e.get("stopOnFailure") : null,
                    e.containsKey("inputContext")
                            ? objectMapper.convertValue(e.get("inputContext"), new TypeReference<Map<String, Object>>() {})
                            : null
            )).toList();

            var request = new CreateChainRequest(
                    name,
                    (String) params.getOrDefault("description", null),
                    (String) params.getOrDefault("icon", "⛓️"),
                    entries,
                    params.containsKey("stopOnFailure") ? (Boolean) params.get("stopOnFailure") : true
            );
            var created = chainService.create(request);
            return "✓ Chain erstellt: \"" + created.getName() + "\" mit " + entries.size() + " Workflows (ID: " + created.getId() + ")";
        } catch (Exception e) {
            log.warn("Assistant create_chain tool failed. Payload: {}", json, e);
            return "⚠ Chain konnte nicht erstellt werden: " + e.getMessage();
        }
    }
}
