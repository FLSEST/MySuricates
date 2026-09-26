package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationVerification;
import com.automation.service.integration.TaskExportCapable.TaskExportInput;
import com.automation.service.integration.TaskExportCapable.TaskRef;
import com.automation.service.integration.TaskExportCapable.TaskTarget;
import com.automation.service.integration.providers.StubApiServer;
import com.automation.service.integration.providers.StubApiServer.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Todoist provider end to end against a local stub shaped after the API v1 reference
 * (paths under {@code /api/v1}, cursor pagination with {@code results}/{@code next_cursor}).
 */
class TodoistProviderTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef01234567";
    private static final String CREDENTIAL = "Mein Todoist|" + TOKEN;
    private static final String TASK_ID = "6XGgmFVcrG5RRjVr";
    private static final String PROJECT_ID = "6XGgm6PHrGgMpCFX";

    private final ObjectMapper mapper = new ObjectMapper();
    private StubApiServer stub;
    private TodoistProvider provider;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApiServer();
        provider = new TodoistProvider(mapper, new TodoistHttpClient(mapper, stub.baseUrl() + "/api/v1"));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private JsonNode lastBody() throws Exception {
        return mapper.readTree(stub.lastRequest().body());
    }

    @Test
    void metadata_directAuthAndSimulated() {
        assertThat(provider.getProviderId()).isEqualTo("todoist");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getVerification()).isEqualTo(IntegrationVerification.SIMULATED);
        assertThat(provider.getVerifiedAgainst()).isNull();
        assertThat(provider.getAvailableActions()).extracting(IntegrationAction::name).containsExactly(
                "list_projects", "list_tasks", "get_task", "create_task", "update_task", "close_task", "add_comment");
    }

    @Test
    void fetchAccountInfo_usesUserEndpointAndDropsEchoedToken() throws Exception {
        stub.onJson("/api/v1/user", 200, "{\"id\":\"1234567\",\"email\":\"maria@example.com\","
                + "\"full_name\":\"Maria Garcia\",\"token\":\"" + TOKEN + "\"}");

        String info = provider.fetchAccountInfo(CREDENTIAL);

        assertThat(mapper.readTree(info).get("email").asText()).isEqualTo("maria@example.com");
        assertThat(mapper.readTree(info).get("name").asText()).isEqualTo("Maria Garcia");
        assertThat(info).doesNotContain(TOKEN);
        assertThat(stub.lastRequest().header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void fetchAccountInfo_invalidToken_returnsNull() {
        stub.onJson("/api/v1/user", 401, "{\"error\":\"Unauthorized\"}");

        assertThat(provider.fetchAccountInfo(CREDENTIAL)).isNull();
    }

    @Test
    void listProjects_passesLimitAndCursorAndReturnsPage() {
        stub.onJson("/api/v1/projects", 200, "{\"results\":[{\"id\":\"" + PROJECT_ID + "\",\"name\":\"Inbox\"}],\"next_cursor\":\"c2\"}");

        Object page = provider.executeAction("list_projects", Map.of("limit", 10, "cursor", "c1+x"), CREDENTIAL);

        assertThat(((Map<?, ?>) page).get("next_cursor")).isEqualTo("c2");
        assertThat(stub.lastRequest().query()).isEqualTo("limit=10&cursor=c1%2Bx");
    }

    @Test
    void listTasks_byProject_usesTasksEndpoint() {
        stub.onJson("/api/v1/tasks", 200, "{\"results\":[],\"next_cursor\":null}");

        provider.executeAction("list_tasks", Map.of("project_id", PROJECT_ID), CREDENTIAL);

        assertThat(stub.lastRequest().path()).isEqualTo("/api/v1/tasks");
        assertThat(stub.lastRequest().query()).isEqualTo("project_id=" + PROJECT_ID + "&limit=50");
    }

    @Test
    void listTasks_byFilter_usesFilterEndpointWithQueryParam() {
        stub.onJson("/api/v1/tasks/filter", 200, "{\"results\":[],\"next_cursor\":null}");

        provider.executeAction("list_tasks", Map.of("filter", "today | overdue"), CREDENTIAL);

        assertThat(stub.lastRequest().path()).isEqualTo("/api/v1/tasks/filter");
        assertThat(stub.lastRequest().query()).isEqualTo("query=today+%7C+overdue&limit=50");
    }

    @Test
    void listTasks_rejectsFilterAndProjectTogether() {
        assertThatThrownBy(() -> provider.executeAction("list_tasks",
                Map.of("filter", "today", "project_id", PROJECT_ID), CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("either 'filter' or 'project_id'");
        assertThat(stub.requests()).isEmpty();
    }

    /** ac639b39: limit 50.5 used to become 50 and priority 4.5 became 4 — read like their text now. */
    @Test
    void limitAndPriority_areReadLikeTheirText() {
        assertThatThrownBy(() -> provider.executeAction("list_tasks", Map.of("limit", 50.5), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be a number");
        assertThatThrownBy(() -> provider.executeAction("create_task", Map.of("content", "x", "priority", 4.5), CREDENTIAL))
                .hasMessage("Parameter 'priority' must be a number between 1 and 4");
        assertThatThrownBy(() -> provider.executeAction("create_task", Map.of("content", "x", "priority", 4_294_967_300L), CREDENTIAL))
                .hasMessage("Parameter 'priority' must be a number between 1 and 4");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void limit_outsideDocumentedRange_isRejected() {
        assertThatThrownBy(() -> provider.executeAction("list_tasks", Map.of("limit", 201), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be between 1 and 200");
        assertThatThrownBy(() -> provider.executeAction("list_projects", Map.of("limit", 0), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be between 1 and 200");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void getTask_usesTaskPath() {
        stub.onJson("/api/v1/tasks/" + TASK_ID, 200, "{\"id\":\"" + TASK_ID + "\"}");

        provider.executeAction("get_task", Map.of("task_id", TASK_ID), CREDENTIAL);

        assertThat(stub.lastRequest().method()).isEqualTo("GET");
    }

    @Test
    void taskId_withPathCharacters_isRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.executeAction("get_task", Map.of("task_id", "../user"), CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parameter 'task_id' is not a valid Todoist id");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void createTask_postsDocumentedFields() throws Exception {
        stub.onJson("/api/v1/tasks", 200, "{\"id\":\"" + TASK_ID + "\",\"content\":\"Buy milk\"}");

        provider.executeAction("create_task", Map.of("content", "Buy milk", "description", "2 Liter",
                "due_string", "tomorrow at 12:00", "priority", "4", "labels", "errands, shopping",
                "project_id", PROJECT_ID), CREDENTIAL);

        JsonNode body = lastBody();
        assertThat(stub.lastRequest().method()).isEqualTo("POST");
        assertThat(body.get("content").asText()).isEqualTo("Buy milk");
        assertThat(body.get("description").asText()).isEqualTo("2 Liter");
        assertThat(body.get("due_string").asText()).isEqualTo("tomorrow at 12:00");
        assertThat(body.get("priority").asInt()).isEqualTo(4);
        assertThat(body.get("labels").isArray()).isTrue();
        assertThat(body.get("labels")).extracting(JsonNode::asText).containsExactly("errands", "shopping");
        assertThat(body.get("project_id").asText()).isEqualTo(PROJECT_ID);
    }

    @Test
    void createTask_validatesContentAndPriority() {
        assertThatThrownBy(() -> provider.executeAction("create_task", Map.of("content", " "), CREDENTIAL))
                .hasMessage("Required parameter missing: content");
        assertThatThrownBy(() -> provider.executeAction("create_task", Map.of("content", "x", "priority", 5), CREDENTIAL))
                .hasMessage("Parameter 'priority' must be between 1 (normal) and 4 (urgent)");
        assertThatThrownBy(() -> provider.executeAction("create_task", Map.of("content", "x", "priority", "hoch"), CREDENTIAL))
                .hasMessage("Parameter 'priority' must be a number between 1 and 4");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void updateTask_postsOnlyGivenFieldsToTaskPath() throws Exception {
        stub.onJson("/api/v1/tasks/" + TASK_ID, 200, "{\"id\":\"" + TASK_ID + "\"}");

        provider.executeAction("update_task", Map.of("task_id", TASK_ID, "due_string", "next monday",
                "labels", List.of("a", " b ")), CREDENTIAL);

        assertThat(stub.lastRequest().method()).isEqualTo("POST");
        JsonNode body = lastBody();
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("due_string", "labels");
        assertThat(body.get("labels")).extracting(JsonNode::asText).containsExactly("a", "b");
    }

    @Test
    void updateTask_withoutChanges_isRejected() {
        assertThatThrownBy(() -> provider.executeAction("update_task", Map.of("task_id", TASK_ID), CREDENTIAL))
                .hasMessageStartingWith("Nothing to update");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void closeTask_postsWithoutBodyAndAcceptsNoContent() {
        stub.on("/api/v1/tasks/" + TASK_ID + "/close", new Response(204, null, Map.of()));

        Object result = provider.executeAction("close_task", Map.of("task_id", TASK_ID), CREDENTIAL);

        assertThat(result).isEqualTo(Map.of("task_id", TASK_ID, "closed", true));
        assertThat(stub.lastRequest().method()).isEqualTo("POST");
        assertThat(stub.lastRequest().body()).isEmpty();
    }

    @Test
    void addComment_postsTaskIdAndContentToComments() throws Exception {
        stub.onJson("/api/v1/comments", 200, "{\"id\":\"6XGgmFQrx44wfGHr\",\"content\":\"Looks good\"}");

        provider.executeAction("add_comment", Map.of("task_id", TASK_ID, "content", "Looks good"), CREDENTIAL);

        JsonNode body = lastBody();
        assertThat(body.get("task_id").asText()).isEqualTo(TASK_ID);
        assertThat(body.get("content").asText()).isEqualTo("Looks good");
        assertThat(body.has("project_id")).as("exactly one of task_id/project_id").isFalse();
    }

    @Test
    void apiError_surfacesMessageWithoutToken() {
        stub.onJson("/api/v1/tasks/" + TASK_ID, 404, "{\"error\":\"Task not found\",\"error_tag\":\"NOT_FOUND\",\"http_code\":404}");

        assertThatThrownBy(() -> provider.executeAction("get_task", Map.of("task_id", TASK_ID), CREDENTIAL))
                .hasMessage("Todoist API error 404: Task not found (NOT_FOUND)")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void unknownAction_throws() {
        assertThatThrownBy(() -> provider.executeAction("delete_everything", Map.of(), CREDENTIAL))
                .hasMessage("Unknown action: delete_everything");
    }

    @Test
    void createTask_export_mapsTodoToTaskInTargetProject() throws Exception {
        stub.onJson("/api/v1/tasks", 200, "{\"id\":\"" + TASK_ID + "\"}");

        TaskRef ref = provider.createTask(new TaskExportInput("Titel", "Beschreibung", List.of("bug"), PROJECT_ID), CREDENTIAL);

        assertThat(ref).isEqualTo(new TaskRef("todoist", TASK_ID, "https://app.todoist.com/app/task/" + TASK_ID));
        JsonNode body = lastBody();
        assertThat(body.get("content").asText()).isEqualTo("Titel");
        assertThat(body.get("description").asText()).isEqualTo("Beschreibung");
        assertThat(body.get("project_id").asText()).isEqualTo(PROJECT_ID);
        assertThat(body.get("labels")).extracting(JsonNode::asText).containsExactly("bug");
    }

    @Test
    void createTask_export_rejectsInvalidTargetBeforeNetwork() {
        assertThatThrownBy(() -> provider.createTask(new TaskExportInput("t", null, List.of(), " "), CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.createTask(new TaskExportInput("t", null, List.of(), "a/b"), CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void createTask_export_withoutIdInAnswer_fails() {
        stub.onJson("/api/v1/tasks", 200, "{}");

        assertThatThrownBy(() -> provider.createTask(new TaskExportInput("t", null, null, PROJECT_ID), CREDENTIAL))
                .hasMessage("Todoist task creation returned no id");
    }

    @Test
    void listTargets_followsNextCursor() {
        stub.onSequence("/api/v1/projects",
                Response.json(200, "{\"results\":[{\"id\":\"p1\",\"name\":\"Inbox\"}],\"next_cursor\":\"c2\"}"),
                Response.json(200, "{\"results\":[{\"id\":\"p2\"}],\"next_cursor\":null}"));

        List<TaskTarget> targets = provider.listTargets(CREDENTIAL);

        assertThat(targets).containsExactly(new TaskTarget("p1", "Inbox"), new TaskTarget("p2", "p2"));
        assertThat(stub.requests()).extracting(StubApiServer.Request::query)
                .containsExactly("limit=200", "limit=200&cursor=c2");
    }

    @Test
    void listTargets_stopsAfterPageCap() {
        stub.onJson("/api/v1/projects", 200, "{\"results\":[{\"id\":\"p1\",\"name\":\"A\"}],\"next_cursor\":\"again\"}");

        List<TaskTarget> targets = provider.listTargets(CREDENTIAL);

        assertThat(stub.requests()).hasSize(5);
        assertThat(stub.lastRequest().query()).isEqualTo("limit=200&cursor=again");
        assertThat(targets).hasSize(5);
    }
}
