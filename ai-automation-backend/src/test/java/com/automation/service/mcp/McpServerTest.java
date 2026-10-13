package com.automation.service.mcp;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.execution.PendingInputService;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.project.ProjectService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.todo.TodoService;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.TemplatePackageInstaller;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ActivityLogService activityLog = mock(ActivityLogService.class);
    private final McpResources resources = mock(McpResources.class);
    private McpServer server;

    @BeforeEach
    void setUp() {
        SystemIdentityService identity = mock(SystemIdentityService.class);
        when(identity.getName()).thenReturn("MySuricates");
        when(identity.getVersion()).thenReturn("9.9.9");
        McpToolset toolset = () -> List.of(
                McpTool.reading("echo", "Echo", "Returns its text",
                        McpSchema.object().string("text", "Text", true).build(),
                        args -> McpToolResult.ok(JsonNodeFactory.instance.objectNode().put("text", args.string("text")))),
                McpTool.destructive("explode", "Explode", "Always fails", McpSchema.none(), args -> {
                    throw new IllegalStateException("Workflow is sealed");
                }),
                McpTool.writing("missing", "Missing", "Unknown id", McpSchema.none(), args -> {
                    throw new ResourceNotFoundException("Workflow", "abc");
                }));
        server = new McpServer(List.of(toolset), resources, new McpPrompts(), identity, activityLog);
    }

    private ObjectNode request(String method, String params) throws Exception {
        return (ObjectNode) json.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":"
                + params + "}");
    }

    private JsonNode answer(String method, String params) throws Exception {
        return server.handle(request(method, params)).orElseThrow();
    }

    @Test
    void initializeEchoesASupportedVersionAndCarriesTheInstructions() throws Exception {
        JsonNode result = answer("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}").get("result");

        assertThat(result.get("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(result.at("/serverInfo/version").asText()).isEqualTo("9.9.9");
        assertThat(result.at("/capabilities/tools").isObject()).isTrue();
        assertThat(result.at("/capabilities/resources").isObject()).isTrue();
        assertThat(result.at("/capabilities/prompts").isObject()).isTrue();
        assertThat(result.get("instructions").asText()).contains("Frugality Ladder", "analyze_workflow");
    }

    @Test
    void initializeOffersTheNewestVersionForAnUnknownOne() throws Exception {
        JsonNode result = answer("initialize", "{\"protocolVersion\":\"1999-01-01\"}").get("result");

        assertThat(result.get("protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSIONS.get(0));
    }

    @Test
    void notificationsAndClientResponsesGetNoAnswer() throws Exception {
        assertThat(server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))).isEmpty();
        assertThat(server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))).isEmpty();
    }

    @Test
    void malformedMessagesAreInvalidRequests() throws Exception {
        assertThat(server.handle(json.readTree("{\"id\":1,\"method\":\"ping\"}")).orElseThrow().at("/error/code").asInt())
                .isEqualTo(McpServer.INVALID_REQUEST);
        assertThat(server.handle(json.readTree("[1]")).orElseThrow().at("/error/code").asInt())
                .isEqualTo(McpServer.INVALID_REQUEST);
    }

    @Test
    void unknownMethodIsMethodNotFoundWithTheRequestId() throws Exception {
        JsonNode answer = answer("sampling/createMessage", "{}");

        assertThat(answer.get("id").asInt()).isEqualTo(7);
        assertThat(answer.at("/error/code").asInt()).isEqualTo(McpServer.METHOD_NOT_FOUND);
    }

    @Test
    void pingAnswersAnEmptyObject() throws Exception {
        assertThat(answer("ping", "{}").get("result").isEmpty()).isTrue();
    }

    @Test
    void toolsListCarriesSchemaAndHints() throws Exception {
        JsonNode tools = answer("tools/list", "{}").at("/result/tools");

        assertThat(tools).hasSize(3);
        JsonNode echo = tools.get(0);
        assertThat(echo.get("name").asText()).isEqualTo("echo");
        assertThat(echo.at("/inputSchema/required/0").asText()).isEqualTo("text");
        assertThat(echo.at("/annotations/readOnlyHint").asBoolean()).isTrue();
        assertThat(echo.at("/annotations/destructiveHint").isMissingNode()).isTrue();
        assertThat(tools.get(1).at("/annotations/destructiveHint").asBoolean()).isTrue();
    }

    /**
     * Pins the hints of every shipped tool. A client asks the user before a destructive tool, so a changed
     * hint is a behaviour change of its own, never the side effect of a refactoring.
     */
    @Test
    void everyShippedToolAnnouncesItsHints() throws Exception {
        WorkflowService workflows = mock(WorkflowService.class);
        List<McpToolset> shipped = List.of(
                new McpGuideTools(mock(McpGuides.class), json),
                new McpCatalogTools(mock(ProjectService.class), mock(ProjectMapper.class), workflows,
                        mock(WorkflowMapper.class), mock(WorkflowTemplateService.class),
                        mock(WorkflowTemplateMapper.class), mock(TemplatePackageInstaller.class),
                        mock(WorkflowPoolService.class), json),
                new McpBuildTools(workflows, mock(WorkflowAnalyzer.class), mock(GeneratedWorkflowCheck.class), json),
                new McpRunTools(workflows, mock(WorkflowExecutor.class), mock(ExecutionQueryService.class),
                        mock(RunControlService.class), mock(PendingInputService.class), json),
                new McpTodoTools(mock(TodoService.class), json));
        McpServer shippedServer = new McpServer(shipped, resources, new McpPrompts(),
                mock(SystemIdentityService.class), activityLog);

        Map<String, String> hints = new TreeMap<>();
        shippedServer.handle(request("tools/list", "{}")).orElseThrow().at("/result/tools").forEach(tool -> {
            JsonNode readOnly = tool.at("/annotations/readOnlyHint");
            JsonNode destructive = tool.at("/annotations/destructiveHint");
            String kind;
            if (readOnly.asBoolean() && destructive.isMissingNode()) {
                kind = "read-only";
            } else if (!readOnly.asBoolean() && destructive.isBoolean()) {
                kind = destructive.asBoolean() ? "destructive" : "writing";
            } else {
                kind = "inconsistent: " + tool.get("annotations");
            }
            hints.put(tool.get("name").asText(), kind);
        });

        assertThat(hints).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("read_guide", "read-only"),
                Map.entry("list_projects", "read-only"),
                Map.entry("list_workflows", "read-only"),
                Map.entry("get_workflow", "read-only"),
                Map.entry("list_templates", "read-only"),
                Map.entry("analyze_workflow", "read-only"),
                Map.entry("get_run", "read-only"),
                Map.entry("list_runs", "read-only"),
                Map.entry("list_pending_inputs", "read-only"),
                Map.entry("list_todos", "read-only"),
                Map.entry("install_template", "writing"),
                Map.entry("create_workflow", "writing"),
                Map.entry("answer_input", "writing"),
                Map.entry("create_todo", "writing"),
                Map.entry("update_todo_status", "writing"),
                // overwrites the definition, starts arbitrary work, ends a run
                Map.entry("update_workflow", "destructive"),
                Map.entry("run_workflow", "destructive"),
                Map.entry("cancel_run", "destructive")));
    }

    @Test
    void toolCallReturnsStructuredContentAndText() throws Exception {
        JsonNode result = answer("tools/call", "{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}").get("result");

        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.at("/structuredContent/text").asText()).isEqualTo("hi");
        assertThat(result.at("/content/0/type").asText()).isEqualTo("text");
        assertThat(result.at("/content/0/text").asText()).contains("\"hi\"");
        verify(activityLog, never()).info(eq(LogCategory.SYSTEM), anyString(), anyString());
    }

    @Test
    void missingArgumentIsAnErrorResultTheModelCanRead() throws Exception {
        JsonNode result = answer("tools/call", "{\"name\":\"echo\",\"arguments\":{}}").get("result");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asText()).isEqualTo("'text' is required");
    }

    @Test
    void refusedWritingToolIsAnErrorResultAndAudited() throws Exception {
        JsonNode result = answer("tools/call", "{\"name\":\"explode\"}").get("result");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asText()).isEqualTo("Workflow is sealed");
        verify(activityLog).warn(eq(LogCategory.SYSTEM), contains("MCP tool explode failed"), eq("McpServer"));
    }

    @Test
    void unknownIdInsideAToolIsAnErrorResult() throws Exception {
        JsonNode result = answer("tools/call", "{\"name\":\"missing\"}").get("result");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asText()).contains("abc");
    }

    @Test
    void unknownToolIsInvalidParams() throws Exception {
        assertThat(answer("tools/call", "{\"name\":\"nope\"}").at("/error/code").asInt())
                .isEqualTo(McpServer.INVALID_PARAMS);
    }

    @Test
    void unknownResourceIsResourceNotFound() throws Exception {
        when(resources.read("mysuricates://nope")).thenThrow(new McpResources.NotFound("mysuricates://nope"));

        assertThat(answer("resources/read", "{\"uri\":\"mysuricates://nope\"}").at("/error/code").asInt())
                .isEqualTo(McpServer.RESOURCE_NOT_FOUND);
    }

    @Test
    void promptsFillTheirArgumentsAndRequireTheMandatoryOnes() throws Exception {
        JsonNode prompt = answer("prompts/get", "{\"name\":\"design_workflow\",\"arguments\":{\"goal\":\"weekly report\","
                + "\"project_id\":\"p-1\"}}").get("result");
        String text = prompt.at("/messages/0/content/text").asText();

        assertThat(text).contains("weekly report", "Project: p-1", "Frugality Ladder").doesNotContain("{{");
        assertThat(answer("prompts/get", "{\"name\":\"design_workflow\",\"arguments\":{}}").at("/error/code").asInt())
                .isEqualTo(McpServer.INVALID_PARAMS);
        assertThat(answer("prompts/list", "{}").at("/result/prompts")).hasSize(McpPrompts.PROMPTS.size());
    }

    @Test
    void everyPromptTextLeavesNoPlaceholderWithoutOptionalArguments() throws Exception {
        for (McpPrompts.Prompt p : McpPrompts.PROMPTS) {
            ObjectNode args = JsonNodeFactory.instance.objectNode();
            p.arguments().stream().filter(McpPrompts.Argument::required).forEach(a -> args.put(a.name(), "x"));
            JsonNode result = answer("prompts/get", "{\"name\":\"" + p.name() + "\",\"arguments\":" + args + "}");
            assertThat(result.at("/result/messages/0/content/text").asText()).as(p.name()).doesNotContain("{{");
        }
    }
}
