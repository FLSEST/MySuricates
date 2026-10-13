package com.automation.service.mcp;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Building workflows: create, update, check. Writes go through {@link WorkflowService} with
 * {@link ChangeSource#MCP} — a version row per change, refused on a sealed workflow, restorable in the UI.
 */
@Component
@RequiredArgsConstructor
public class McpBuildTools implements McpToolset {

    private static final List<String> CREATE_FIELDS = List.of("projectId", "name", "description", "icon", "blocks",
            "instructions", "model", "provider", "effort", "metadata", "defaultContext", "version");
    private static final List<String> UPDATE_FIELDS = List.of("name", "description", "icon", "blocks",
            "instructions", "model", "provider", "effort", "metadata", "defaultContext", "changeMessage", "tags",
            "category", "version");

    private static final String BLOCKS_DOC = "The steps in order. Each block: {\"type\": \"shell\"|\"simple\"|"
            + "\"agentic\"|\"approval\"|\"human-input\"|\"foreach\"|\"conditional\"|..., \"name\": \"...\", "
            + "\"config\": {...}} — e.g. {\"type\":\"shell\",\"name\":\"collect\",\"config\":{\"command\":\"git log "
            + "--oneline -20\"}} then {\"type\":\"simple\",\"name\":\"summarize\",\"config\":{\"command\":\"Summarize: "
            + "{{ previous_output }}\"}}. Config keys per type: read_guide topic 'block-reference'.";

    private final WorkflowService workflowService;
    private final WorkflowAnalyzer analyzer;
    private final GeneratedWorkflowCheck buildCheck;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpTool> tools() {
        return List.of(
                McpTool.writing("create_workflow", "Create workflow",
                        "Creates a workflow in a project. Prefer deterministic steps: shell before simple (one "
                                + "AI call) before agentic (many calls) — read_guide topic 'workflow-guide' section "
                                + "'Frugality Ladder'. Returns the id and the build check's findings; fix them with "
                                + "update_workflow, then try it with run_workflow.",
                        McpSchema.object()
                                .string("projectId", "Project id (list_projects)", true)
                                .string("name", "Name", true)
                                .string("description", "What it does, one or two sentences", false)
                                .objectArray("blocks", BLOCKS_DOC, true)
                                .string("instructions", "Instructions for every AI step of this workflow", false)
                                .string("model", "Model alias for AI steps, e.g. 'sonnet', 'opus', 'haiku' "
                                        + "(default: system setting)", false)
                                .string("provider", "AI provider: CLAUDE, PI or CODEX (default: system setting)", false)
                                .enumeration("effort", "Reasoning effort", List.of("low", "medium", "high", "xhigh"), false)
                                .objectProperty("defaultContext", "Default values of the variables the blocks use", false)
                                .build(),
                        this::createWorkflow),
                McpTool.destructive("update_workflow", "Update workflow",
                        "Changes a workflow: only the fields passed, the rest stays. 'blocks' replaces all "
                                + "blocks — send the complete list (get_workflow returns it). Every change is a "
                                + "new version that can be restored in the UI. Returns the build check's findings.",
                        McpSchema.object()
                                .string("id", "Workflow id", true)
                                .string("name", "New name", false)
                                .string("description", "New description", false)
                                .objectArray("blocks", "The complete new block list. " + BLOCKS_DOC, false)
                                .string("instructions", "New instructions for the AI steps", false)
                                .string("model", "New model alias", false)
                                .string("provider", "New AI provider", false)
                                .enumeration("effort", "Reasoning effort", List.of("low", "medium", "high", "xhigh"), false)
                                .objectProperty("defaultContext", "New default context", false)
                                .string("changeMessage", "Why it changed (shown in the version history)", false)
                                .build(),
                        this::updateWorkflow),
                McpTool.reading("analyze_workflow", "Analyze workflow",
                        "Checks a workflow definition deterministically for build mistakes (unknown config keys, "
                                + "missing required keys, timeouts, platform access from shell steps, ...). Reports "
                                + "only, changes nothing. Run it after every create or update.",
                        McpSchema.object()
                                .string("id", "Workflow id", true)
                                .enumeration("lang", "Language of the findings (default en)", List.of("de", "en"), false)
                                .build(),
                        this::analyzeWorkflow));
    }

    private McpToolResult createWorkflow(McpArgs args) {
        args.string("projectId");
        args.string("name");
        if (!args.raw().path("blocks").isArray()) {
            throw new McpArgs.InvalidArgument("'blocks' must be an array of blocks");
        }
        CreateWorkflowRequest request = objectMapper.convertValue(pick(args, CREATE_FIELDS), CreateWorkflowRequest.class);
        Workflow created = workflowService.create(request, ChangeSource.MCP);
        return McpToolResult.ok(saved(created));
    }

    private McpToolResult updateWorkflow(McpArgs args) {
        String id = args.string("id");
        if (args.has("blocks") && !args.raw().path("blocks").isArray()) {
            throw new McpArgs.InvalidArgument("'blocks' must be an array of blocks");
        }
        UpdateWorkflowRequest request = objectMapper.convertValue(pick(args, UPDATE_FIELDS), UpdateWorkflowRequest.class);
        Workflow updated = workflowService.update(id, request, ChangeSource.MCP);
        return McpToolResult.ok(saved(updated));
    }

    private McpToolResult analyzeWorkflow(McpArgs args) {
        Workflow workflow = workflowService.findByIdEager(args.string("id"));
        String lang = args.locale("lang");
        List<AnalyzerFinding> findings = analyzer.analyze(workflow);
        Map<AnalyzerFinding.Severity, Integer> counts = new EnumMap<>(AnalyzerFinding.Severity.class);
        findings.forEach(f -> counts.merge(f.severity(), 1, Integer::sum));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("workflowId", workflow.getId());
        data.put("name", workflow.getName());
        data.set("counts", objectMapper.valueToTree(counts));
        data.set("findings", findingsJson(findings, lang));
        return McpToolResult.ok(data);
    }

    /** Id and name of the saved workflow plus the build check (findings from MEDIUM up). */
    private ObjectNode saved(Workflow workflow) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", workflow.getId());
        data.put("name", workflow.getName());
        List<AnalyzerFinding> findings = buildCheck.findings(workflow);
        data.set("buildCheck", findingsJson(findings, "en"));
        data.put("next", findings.isEmpty()
                ? "No findings from MEDIUM up. Try it with run_workflow; analyze_workflow lists the LOW ones too."
                : "Fix the findings with update_workflow, then run analyze_workflow again.");
        return data;
    }

    private ArrayNode findingsJson(List<AnalyzerFinding> findings, String lang) {
        ArrayNode list = objectMapper.createArrayNode();
        for (AnalyzerFinding f : findings) {
            ObjectNode node = list.addObject();
            node.put("severity", f.severity().name());
            node.put("rule", f.rule());
            node.put("blockIndex", f.blockIndex());
            node.put("blockName", f.blockName());
            node.put("detail", "en".equals(lang) && f.detailEn() != null ? f.detailEn() : f.detail());
        }
        return list;
    }

    /** Only the fields a client may set — no skills, template link or live-log setting through this way. */
    private ObjectNode pick(McpArgs args, List<String> fields) {
        ObjectNode picked = objectMapper.createObjectNode();
        fields.forEach(field -> {
            if (args.has(field)) {
                picked.set(field, args.raw().get(field));
            }
        });
        return picked;
    }
}
