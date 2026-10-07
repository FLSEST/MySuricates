package com.automation.service.mcp;

import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.dto.response.ProjectResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.Workflow;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.TemplateResponseLocalizer;
import com.automation.mapper.WorkflowMapper;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.TemplatePackageInstaller;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finding one's way: projects, workflows and templates (templates, blueprints, pool).
 * Everything here only reads, except {@code install_template}.
 */
@Component
@RequiredArgsConstructor
public class McpCatalogTools implements McpToolset {

    private static final int DESCRIPTION_PREVIEW = 300;

    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final WorkflowTemplateService templateService;
    private final WorkflowTemplateMapper templateMapper;
    private final TemplatePackageInstaller packageInstaller;
    private final WorkflowPoolService poolService;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpTool> tools() {
        return List.of(
                McpTool.reading("list_projects", "List projects",
                        "Lists the projects (a project is a directory on the host; its workflows run there). "
                                + "Workflows and TODOs belong to a project — pass its id to create_workflow, "
                                + "install_template and create_todo.",
                        McpSchema.none(), args -> listProjects()),
                McpTool.reading("list_workflows", "List workflows",
                        "Lists workflows, most recently changed first: id, name, description, project, number "
                                + "of blocks and runs. Use get_workflow for the full definition.",
                        McpSchema.object()
                                .string("projectId", "Only workflows of this project", false)
                                .string("query", "Text in name, description or tags (case-insensitive)", false)
                                .integer("limit", "Maximum number of entries (default 50)", 1, 200)
                                .build(),
                        this::listWorkflows),
                McpTool.reading("get_workflow", "Get workflow",
                        "Returns the full definition of a workflow: blocks with their config, instructions, "
                                + "model, provider, default context, metadata. The same shape update_workflow takes.",
                        McpSchema.object().string("id", "Workflow id", true).build(),
                        args -> McpToolResult.ok(objectMapper.valueToTree(
                                workflowMapper.toResponse(workflowService.findById(args.string("id")))))),
                McpTool.reading("list_templates", "List templates",
                        "Lists what a new workflow can start from. 'template': ready-made workflows; "
                                + "'blueprint': small bilingual scaffolds of one pattern each (approval, loop, "
                                + "gate, form, report, ...) with runnable placeholders; 'pool': shared example "
                                + "workflows. Start from one of these before building from scratch.",
                        McpSchema.object()
                                .enumeration("kind", "Which kind (default all)",
                                        List.of("all", "template", "blueprint", "pool"), false)
                                .string("query", "Text in id, name, description or category", false)
                                .enumeration("locale", "Language of names and descriptions (default en)",
                                        List.of("de", "en"), false)
                                .build(),
                        this::listTemplates),
                McpTool.writing("install_template", "Install template",
                        "Creates a workflow in a project from a template, blueprint or pool entry "
                                + "(source 'template' covers blueprints). Returns the new workflow's id; "
                                + "adjust it with update_workflow, check it with analyze_workflow.",
                        McpSchema.object()
                                .enumeration("source", "Where the id comes from", List.of("template", "pool"), true)
                                .string("id", "Template or pool id from list_templates", true)
                                .string("projectId", "Target project", true)
                                .string("name", "Name of the new workflow (default: the template's)", false)
                                .enumeration("locale", "Language of the created steps (default en)",
                                        List.of("de", "en"), false)
                                .build(),
                        false, this::installTemplate));
    }

    private McpToolResult listProjects() {
        ArrayNode list = objectMapper.createArrayNode();
        for (ProjectResponse p : projectMapper.toResponseList(projectService.findAll())) {
            ObjectNode node = list.addObject();
            node.put("id", p.id());
            node.put("name", p.name());
            node.put("description", preview(p.description()));
            node.put("path", p.path());
            node.put("workflowCount", p.workflowCount());
        }
        return McpToolResult.ok("projects", list);
    }

    private McpToolResult listWorkflows(McpArgs args) {
        String projectId = args.optionalString("projectId");
        String query = lower(args.optionalString("query"));
        int limit = args.integer("limit", 50, 1, 200);
        List<Workflow> workflows = projectId != null
                ? workflowService.findByProjectId(projectId)
                : workflowService.findAll();
        List<WorkflowResponse> matching = workflowMapper.toResponseListWithRunCounts(workflows).stream()
                .filter(w -> query == null || contains(w.name(), query) || contains(w.description(), query)
                        || (w.tags() != null && w.tags().stream().anyMatch(t -> contains(t, query))))
                .sorted(Comparator.comparing(WorkflowResponse::updatedAt,
                        Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                .toList();
        ArrayNode list = objectMapper.createArrayNode();
        matching.stream().limit(limit).forEach(w -> {
            ObjectNode node = list.addObject();
            node.put("id", w.id());
            node.put("name", w.name());
            node.put("description", preview(w.description()));
            node.put("projectId", w.projectId());
            node.put("projectName", w.projectName());
            node.put("blocks", w.blocks() == null ? 0 : w.blocks().size());
            node.put("runCount", w.runCount());
            node.put("sealed", w.sealed());
            node.set("tags", objectMapper.valueToTree(w.tags() == null ? List.of() : w.tags()));
            node.put("updatedAt", w.updatedAt() == null ? null : w.updatedAt().toString());
        });
        ObjectNode data = objectMapper.createObjectNode();
        data.put("total", matching.size());
        data.set("workflows", list);
        return McpToolResult.ok(data);
    }

    private McpToolResult listTemplates(McpArgs args) {
        String kind = args.choice("kind", "all", List.of("all", "template", "blueprint", "pool"));
        String query = lower(args.optionalString("query"));
        String locale = args.locale("locale");
        ArrayNode list = objectMapper.createArrayNode();
        if (!kind.equals("pool")) {
            var templates = templateService.findAll();
            for (WorkflowTemplateResponse t : TemplateResponseLocalizer.localize(templates,
                    templateMapper.toResponseList(templates), locale)) {
                String tKind = t.kind() == null ? "template" : t.kind().name().toLowerCase(Locale.ROOT);
                if (!kind.equals("all") && !kind.equals(tKind)) {
                    continue;
                }
                String name = text(t.name(), locale);
                String description = text(t.description(), locale);
                if (!matches(query, t.id(), name, description, t.category())) {
                    continue;
                }
                ObjectNode node = list.addObject();
                node.put("source", "template");
                node.put("kind", tKind);
                node.put("id", t.id());
                node.put("name", name);
                node.put("description", preview(description));
                node.put("category", t.category());
                node.put("blocks", t.blocks() == null ? 0 : t.blocks().size());
                node.put("needsAi", t.needsAi());
                node.set("requiredModules", objectMapper.valueToTree(
                        t.requiredModules() == null ? List.of() : t.requiredModules()));
            }
        }
        if (kind.equals("all") || kind.equals("pool")) {
            for (PoolWorkflowEntry p : poolService.listAll(locale)) {
                String name = text(p.name(), locale);
                String description = text(p.description(), locale);
                if (!matches(query, p.id(), name, description, p.category())) {
                    continue;
                }
                ObjectNode node = list.addObject();
                node.put("source", "pool");
                node.put("kind", "pool");
                node.put("id", p.id());
                node.put("name", name);
                node.put("description", preview(description));
                node.put("category", p.category());
                node.put("blocks", p.blocks() == null ? 0 : p.blocks().size());
            }
        }
        return McpToolResult.ok("templates", list);
    }

    private McpToolResult installTemplate(McpArgs args) {
        String source = args.choice("source", null, List.of("template", "pool"));
        if (source == null) {
            throw new McpArgs.InvalidArgument("'source' is required");
        }
        String id = args.string("id");
        String projectId = args.string("projectId");
        String name = args.optionalString("name");
        String locale = args.locale("locale");
        ObjectNode data = objectMapper.createObjectNode();
        if (source.equals("template")) {
            var installed = packageInstaller.install(id, projectId, name, null, locale, null);
            data.put("workflowId", installed.workflow().getId());
            data.put("name", installed.workflow().getName());
            ArrayNode parts = data.putArray("partWorkflowIds");
            installed.parts().forEach(w -> parts.add(w.getId()));
        } else {
            var result = poolService.install(id, new InstallPoolWorkflowRequest(projectId, name, false, locale));
            data.put("workflowId", result.workflow().getId());
            data.put("name", result.workflow().getName());
            data.put("created", result.created());
            data.put("integrity", result.integrity().name());
        }
        return McpToolResult.ok(data);
    }

    private static String text(Map<String, String> localized, String locale) {
        if (localized == null || localized.isEmpty()) {
            return null;
        }
        String value = localized.get(locale);
        return value != null ? value : localized.getOrDefault("en", localized.values().iterator().next());
    }

    private static boolean matches(String query, String... fields) {
        if (query == null) {
            return true;
        }
        for (String field : fields) {
            if (contains(field, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    static String preview(String text) {
        if (text == null || text.length() <= DESCRIPTION_PREVIEW) {
            return text;
        }
        return text.substring(0, DESCRIPTION_PREVIEW) + "…";
    }
}
