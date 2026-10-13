package com.automation.service.mcp;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.service.todo.TodoService;
import com.automation.util.EnumParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** TODOs: list, create, set the status. A TODO created here carries source AI and agent {@code mcp}. */
@Component
@RequiredArgsConstructor
public class McpTodoTools implements McpToolset {

    /** {@code sourceAgent} of TODOs created through MCP. */
    static final String SOURCE_AGENT = "mcp";

    private static final List<String> STATUSES = Arrays.stream(Todo.Status.values()).map(Enum::name).toList();
    private static final List<String> PRIORITIES = Arrays.stream(Todo.Priority.values()).map(Enum::name).toList();

    private final TodoService todoService;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpTool> tools() {
        return List.of(
                McpTool.reading("list_todos", "List TODOs",
                        "Lists TODOs, by default the open ones (OPEN, IN_PROGRESS).",
                        McpSchema.object()
                                .string("projectId", "Only TODOs of this project", false)
                                .stringArray("status", "Statuses to include (default OPEN and IN_PROGRESS): "
                                        + String.join(", ", STATUSES))
                                .string("query", "Text in title or description", false)
                                .integer("limit", "Maximum number of entries (default 50)", 1, 200)
                                .build(),
                        this::listTodos),
                McpTool.writing("create_todo", "Create TODO",
                        "Creates a TODO (status OPEN). Write the title as a short imperative.",
                        McpSchema.object()
                                .string("title", "Short imperative title", true)
                                .string("description", "Markdown with context and steps", false)
                                .enumeration("priority", "Priority (default MEDIUM)", PRIORITIES, false)
                                .stringArray("tags", "Tags")
                                .string("projectId", "Project id", false)
                                .string("linkedWorkflowId", "Workflow that can resolve it", false)
                                .build(),
                        this::createTodo),
                McpTool.writing("update_todo_status", "Set TODO status",
                        "Sets the status of a TODO, e.g. IN_PROGRESS when work starts and DONE when it is finished.",
                        McpSchema.object()
                                .string("id", "TODO id", true)
                                .enumeration("status", "New status", STATUSES, true)
                                .build(),
                        this::updateStatus));
    }

    private McpToolResult listTodos(McpArgs args) {
        List<String> status = args.stringList("status");
        int limit = args.integer("limit", 50, 1, 200);
        var page = todoService.findAll(0, limit, args.optionalString("projectId"),
                status.isEmpty() ? List.of("OPEN", "IN_PROGRESS") : status, null, null, null,
                args.optionalString("query"));
        ArrayNode list = objectMapper.createArrayNode();
        for (TodoResponse t : page.content()) {
            ObjectNode node = list.addObject();
            node.put("id", t.id());
            node.put("title", t.title());
            node.put("status", t.status() == null ? null : t.status().name());
            node.put("priority", t.priority() == null ? null : t.priority().name());
            node.set("tags", objectMapper.valueToTree(t.tags() == null ? List.of() : t.tags()));
            node.put("description", McpCatalogTools.preview(t.description()));
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("total", page.totalElements());
        data.set("todos", list);
        return McpToolResult.ok(data);
    }

    private McpToolResult createTodo(McpArgs args) {
        Todo.Priority priority = priority(args.optionalString("priority"));
        var request = new CreateTodoRequest(args.string("title"), args.optionalString("description"),
                Todo.Status.OPEN, priority, args.stringList("tags"), args.optionalString("projectId"),
                args.optionalString("linkedWorkflowId"), Todo.Source.AI, SOURCE_AGENT, null, null, null, null, null,
                null);
        Todo created = todoService.create(request);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", created.getId());
        data.put("title", created.getTitle());
        data.put("priority", created.getPriority() == null ? null : created.getPriority().name());
        return McpToolResult.ok(data);
    }

    private McpToolResult updateStatus(McpArgs args) {
        String id = args.string("id");
        Todo.Status status = parse(Todo.Status.class, "status", args.string("status"));
        todoService.updateStatus(id, new UpdateTodoStatusRequest(status, null));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", id);
        data.put("status", status.name());
        return McpToolResult.ok(data);
    }

    private static Todo.Priority priority(String value) {
        Todo.Priority priority = value == null ? null : parse(Todo.Priority.class, "priority", value);
        return priority != null ? priority : Todo.Priority.MEDIUM;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
        try {
            return EnumParameter.parse(type, name, value);
        } catch (IllegalArgumentException e) {
            throw new McpArgs.InvalidArgument(e.getMessage());
        }
    }
}
