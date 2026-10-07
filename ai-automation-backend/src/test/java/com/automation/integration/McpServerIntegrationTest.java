package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MCP endpoint on the real stack (integration profile = preset full, so the module is on): protocol
 * handshake, transport rules, and a client's way from creating a workflow to its finished run — with the
 * change recorded as {@code mcp} and the run started as {@code MCP}.
 *
 * <p>Without a test transaction, because the run has its own transactions and thread; {@link #tearDown}
 * cleans up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class McpServerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private JdbcTemplate jdbc;

    @TempDir Path tempDir;

    private String projectId;
    private final List<String> workflowIds = new ArrayList<>();
    private int nextId = 1;

    @BeforeEach
    void setUp() {
        projectId = projectRepository.save(Project.builder().name("MCP " + System.nanoTime())
                .type(ProjectType.CODE).path(tempDir.toString()).status(ProjectStatus.STOPPED).build()).getId();
    }

    @AfterEach
    void tearDown() {
        for (String workflowId : workflowIds) {
            jdbc.update("DELETE FROM step_results WHERE workflow_run_id IN (SELECT id FROM workflow_runs WHERE workflow_id = ?)",
                    workflowId);
            jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM workflow_runs WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        }
        jdbc.update("DELETE FROM todos WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(body));
    }

    private JsonNode rpc(String method, Object params) throws Exception {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", nextId++);
        message.put("method", method);
        message.set("params", objectMapper.valueToTree(params));
        String body = send(message.toString()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** The {@code result} of a tools/call, asserting the call itself succeeded. */
    private JsonNode tool(String name, Object arguments) throws Exception {
        JsonNode result = rpc("tools/call", java.util.Map.of("name", name, "arguments", arguments)).get("result");
        assertThat(result.get("isError").asBoolean()).as(name + ": " + result.at("/content/0/text").asText()).isFalse();
        return result.get("structuredContent");
    }

    @Test
    void handshakeListsToolsResourcesAndPrompts() throws Exception {
        JsonNode init = rpc("initialize", java.util.Map.of("protocolVersion", "2025-06-18",
                "capabilities", java.util.Map.of(), "clientInfo", java.util.Map.of("name", "it", "version", "1")));
        assertThat(init.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.at("/result/serverInfo/name").asText()).isEqualTo("mysuricates");

        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").andExpect(status().isAccepted());

        List<String> tools = new ArrayList<>();
        rpc("tools/list", java.util.Map.of()).at("/result/tools").forEach(t -> tools.add(t.get("name").asText()));
        assertThat(tools).containsExactlyInAnyOrder("list_projects", "list_workflows", "get_workflow", "list_templates",
                "install_template", "read_guide", "create_workflow", "update_workflow", "analyze_workflow",
                "run_workflow", "get_run", "list_runs", "cancel_run", "list_pending_inputs", "answer_input",
                "list_todos", "create_todo", "update_todo_status");

        JsonNode resources = rpc("resources/list", java.util.Map.of()).at("/result/resources");
        assertThat(resources.findValuesAsText("uri")).contains("mysuricates://docs/en/workflow-guide");
        JsonNode guide = rpc("resources/read", java.util.Map.of("uri", "mysuricates://docs/en/workflow-guide"));
        assertThat(guide.at("/result/contents/0/text").asText()).contains("Frugality Ladder");
        assertThat(rpc("resources/read", java.util.Map.of("uri", "mysuricates://runs/nope")).at("/error/code").asInt())
                .isEqualTo(-32002);

        JsonNode section = tool("read_guide", java.util.Map.of("topic", "workflow-guide", "section", "Audit Pipeline"));
        assertThat(section.get("section").asText()).startsWith("### Pattern 6: Audit Pipeline");

        assertThat(rpc("prompts/list", java.util.Map.of()).at("/result/prompts").size()).isEqualTo(3);
    }

    @Test
    void transportRules() throws Exception {
        mockMvc.perform(get("/api/v1/mcp")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"));
        mockMvc.perform(delete("/api/v1/mcp")).andExpect(status().isMethodNotAllowed());
        send("{not json").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON).header("Origin", "https://evil.example")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON).header("Origin", "http://localhost:3000")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON).header("MCP-Protocol-Version", "2020-01-01")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isBadRequest());

        String batch = send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}]")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode answers = objectMapper.readTree(batch);
        assertThat(answers).hasSize(2);
        assertThat(answers.get(1).at("/error/code").asInt()).isEqualTo(-32601);
    }

    @Test
    void clientBuildsChecksAndRunsAWorkflow() throws Exception {
        JsonNode created = tool("create_workflow", java.util.Map.of(
                "projectId", projectId,
                "name", "MCP hello",
                "blocks", List.of(java.util.Map.of("type", "shell", "name", "hello",
                        "config", java.util.Map.of("command", "echo hello-from-mcp")))));
        String workflowId = created.get("id").asText();
        workflowIds.add(workflowId);
        assertThat(created.get("buildCheck").isArray()).isTrue();
        assertThat(jdbc.queryForObject("SELECT change_source FROM workflow_versions WHERE workflow_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, workflowId)).isEqualTo("mcp");

        JsonNode listed = tool("list_workflows", java.util.Map.of("projectId", projectId));
        assertThat(listed.at("/workflows/0/id").asText()).isEqualTo(workflowId);
        assertThat(tool("get_workflow", java.util.Map.of("id", workflowId)).at("/blocks/0/config/command").asText())
                .isEqualTo("echo hello-from-mcp");
        assertThat(tool("analyze_workflow", java.util.Map.of("id", workflowId)).get("findings").isArray()).isTrue();

        tool("update_workflow", java.util.Map.of("id", workflowId, "description", "Says hello",
                "changeMessage", "from the test"));
        assertThat(tool("get_workflow", java.util.Map.of("id", workflowId)).get("description").asText()).isEqualTo("Says hello");

        JsonNode run = tool("run_workflow", java.util.Map.of("workflowId", workflowId, "waitSeconds", 60));
        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("triggerSource").asText()).isEqualTo("MCP");
        assertThat(run.at("/steps/0/output").asText()).contains("hello-from-mcp");

        String runId = run.get("id").asText();
        assertThat(tool("get_run", java.util.Map.of("runId", runId, "step", 0)).get("output").asText())
                .contains("hello-from-mcp");
        assertThat(tool("list_runs", java.util.Map.of("workflowId", workflowId)).at("/runs/0/id").asText()).isEqualTo(runId);
        JsonNode resource = rpc("resources/read", java.util.Map.of("uri", "mysuricates://runs/" + runId));
        assertThat(resource.at("/result/contents/0/text").asText()).contains("hello-from-mcp");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE source = 'McpServer' AND message LIKE 'MCP tool run_workflow%'",
                Integer.class)).isPositive();
    }

    @Test
    void mistakesComeBackAsReadableToolErrors() throws Exception {
        JsonNode unknownWorkflow = rpc("tools/call", java.util.Map.of("name", "get_workflow",
                "arguments", java.util.Map.of("id", "does-not-exist"))).get("result");
        assertThat(unknownWorkflow.get("isError").asBoolean()).isTrue();

        JsonNode missingBlocks = rpc("tools/call", java.util.Map.of("name", "create_workflow",
                "arguments", java.util.Map.of("projectId", projectId, "name", "x"))).get("result");
        assertThat(missingBlocks.get("isError").asBoolean()).isTrue();
        assertThat(missingBlocks.at("/content/0/text").asText()).contains("blocks");

        assertThat(rpc("tools/call", java.util.Map.of("name", "no_such_tool")).at("/error/code").asInt()).isEqualTo(-32602);
    }

    @Test
    void todosCreatedThroughMcpCarryTheirOrigin() throws Exception {
        JsonNode todo = tool("create_todo", java.util.Map.of("title", "Check the MCP server", "projectId", projectId,
                "priority", "HIGH", "tags", List.of("mcp")));
        String id = todo.get("id").asText();
        assertThat(jdbc.queryForObject("SELECT source_agent FROM todos WHERE id = ?", String.class, id)).isEqualTo("mcp");

        tool("update_todo_status", java.util.Map.of("id", id, "status", "IN_PROGRESS"));
        JsonNode open = tool("list_todos", java.util.Map.of("projectId", projectId));
        assertThat(open.at("/todos/0/status").asText()).isEqualTo("IN_PROGRESS");
    }
}
