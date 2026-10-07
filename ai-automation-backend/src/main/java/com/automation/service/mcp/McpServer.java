package com.automation.service.mcp;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.SystemIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Model Context Protocol on top of the platform's services: JSON-RPC 2.0 messages in, answers out.
 * Stateless — no session, no server-initiated messages; every request is answered on its own.
 *
 * <p>Offers tools ({@link McpToolset} beans), resources ({@link McpResources}) and prompts
 * ({@link McpPrompts}). A tool that fails in a way the model can act on returns an error result
 * ({@code isError}); protocol mistakes (unknown method, unknown tool, malformed message) are JSON-RPC errors.
 */
@Service
@Slf4j
public class McpServer {

    /** Supported protocol revisions, newest first; the first is offered when a client asks for another. */
    public static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int RESOURCE_NOT_FOUND = -32002;

    private final Map<String, McpTool> tools;
    private final McpResources resources;
    private final McpPrompts prompts;
    private final SystemIdentityService identity;
    private final ActivityLogService activityLog;
    private final String instructions;

    public McpServer(List<McpToolset> toolsets, McpResources resources, McpPrompts prompts,
                     SystemIdentityService identity, ActivityLogService activityLog) {
        Map<String, McpTool> byName = new LinkedHashMap<>();
        for (McpToolset toolset : toolsets) {
            for (McpTool tool : toolset.tools()) {
                if (byName.put(tool.name(), tool) != null) {
                    throw new IllegalStateException("Duplicate MCP tool name: " + tool.name());
                }
            }
        }
        this.tools = byName;
        this.resources = resources;
        this.prompts = prompts;
        this.identity = identity;
        this.activityLog = activityLog;
        this.instructions = classpathText("mcp/instructions.md");
    }

    /** Names of every tool, in announcement order. */
    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    /**
     * Handles one JSON-RPC message. Empty for a notification or a response sent by the client — those
     * get no answer (HTTP 202).
     */
    public Optional<ObjectNode> handle(JsonNode message) {
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            return Optional.of(error(idOf(message), INVALID_REQUEST, "Not a JSON-RPC 2.0 message"));
        }
        JsonNode method = message.get("method");
        if (method == null || !method.isTextual()) {
            // A response to a server request — this server sends none, so there is nothing to match it to.
            return message.has("result") || message.has("error")
                    ? Optional.empty()
                    : Optional.of(error(idOf(message), INVALID_REQUEST, "Missing method"));
        }
        if (!message.has("id")) {
            return Optional.empty(); // notifications/initialized, notifications/cancelled, ...
        }
        JsonNode id = message.get("id");
        JsonNode params = message.path("params");
        try {
            return Optional.of(result(id, dispatch(method.asText(), params)));
        } catch (McpArgs.InvalidArgument e) {
            return Optional.of(error(id, INVALID_PARAMS, e.getMessage()));
        } catch (MethodNotFound e) {
            return Optional.of(error(id, METHOD_NOT_FOUND, e.getMessage()));
        } catch (McpResources.NotFound | ResourceNotFoundException e) {
            return Optional.of(error(id, RESOURCE_NOT_FOUND, e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("MCP {} failed", method.asText(), e);
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error"));
        }
    }

    /** A JSON-RPC error answer without a request id — for a body that is no JSON at all. */
    public static ObjectNode parseError() {
        return error(null, PARSE_ERROR, "Parse error");
    }

    private static final class MethodNotFound extends RuntimeException {
        MethodNotFound(String method) {
            super("Method not found: " + method);
        }
    }

    private JsonNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> JsonNodeFactory.instance.objectNode();
            case "tools/list" -> listTools();
            case "tools/call" -> callTool(params);
            case "resources/list" -> resources.list();
            case "resources/templates/list" -> resources.templates();
            case "resources/read" -> resources.read(requiredText(params, "uri"));
            case "prompts/list" -> prompts.list();
            case "prompts/get" -> prompts.get(requiredText(params, "name"), params.get("arguments"));
            default -> throw new MethodNotFound(method);
        };
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        capabilities.putObject("resources").put("subscribe", false).put("listChanged", false);
        capabilities.putObject("prompts").put("listChanged", false);
        ObjectNode server = result.putObject("serverInfo");
        server.put("name", "mysuricates");
        server.put("title", identity.getName());
        server.put("version", identity.getVersion());
        result.put("instructions", instructions);
        return result;
    }

    private ObjectNode listTools() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode list = result.putArray("tools");
        tools.values().forEach(t -> list.add(t.definition()));
        return result;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = requiredText(params, "name");
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new McpArgs.InvalidArgument("Unknown tool: " + name);
        }
        McpToolResult outcome = run(tool, params.get("arguments"));
        if (!tool.readOnly()) {
            audit(tool, outcome);
        }
        return outcome.toJson();
    }

    /** Runs the tool; every failure becomes an error result the model can read. */
    private McpToolResult run(McpTool tool, JsonNode arguments) {
        try {
            return tool.handler().call(new McpArgs(arguments));
        } catch (McpArgs.InvalidArgument | ResourceNotFoundException | IllegalArgumentException
                 | IllegalStateException e) {
            return McpToolResult.error(e.getMessage());
        } catch (ResponseStatusException e) {
            return McpToolResult.error(e.getReason() != null ? e.getReason() : e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP tool {} failed", tool.name(), e);
            return McpToolResult.error(e.getMessage() != null ? e.getMessage()
                    : "The tool failed (" + e.getClass().getSimpleName() + ") — see the server log.");
        }
    }

    /** Every writing call lands in the activity log, like the assistant's confirmed tools. */
    private void audit(McpTool tool, McpToolResult outcome) {
        try {
            if (outcome.isError()) {
                activityLog.warn(LogCategory.SYSTEM, "MCP tool " + tool.name() + " failed: " + outcome.message(),
                        "McpServer");
            } else {
                activityLog.info(LogCategory.SYSTEM, "MCP tool " + tool.name() + " executed", "McpServer");
            }
        } catch (RuntimeException e) {
            log.warn("MCP audit log failed for tool {}: {}", tool.name(), e.getMessage());
        }
    }

    private static String requiredText(JsonNode params, String field) {
        JsonNode value = params.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new McpArgs.InvalidArgument("'" + field + "' is required");
        }
        return value.asText();
    }

    private static JsonNode idOf(JsonNode message) {
        return message.isObject() ? message.get("id") : null;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static String classpathText(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path, e);
        }
    }
}
