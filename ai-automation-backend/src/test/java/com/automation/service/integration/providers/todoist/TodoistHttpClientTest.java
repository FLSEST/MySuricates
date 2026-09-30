package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.providers.StubApiServer;
import com.automation.service.integration.providers.StubApiServer.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transport and error mapping of the Todoist API v1 client against a local stub. */
class TodoistHttpClientTest {

    private static final String TOKEN = "test-todoist-token";

    private StubApiServer stub;
    private TodoistHttpClient client;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApiServer();
        client = new TodoistHttpClient(new ObjectMapper(), stub.baseUrl() + "/api/v1");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void defaultBaseUrlIsApiV1() {
        assertThat(TodoistHttpClient.DEFAULT_BASE_URL).isEqualTo("https://api.todoist.com/api/v1");
    }

    @Test
    void get_sendsBearerTokenAndParsesJson() {
        stub.onJson("/api/v1/projects", 200, "{\"results\":[{\"id\":\"6XGgm6PHrGgMpCFX\"}],\"next_cursor\":null}");

        Object page = client.get("/projects?limit=50", TOKEN);

        assertThat(((Map<?, ?>) page).get("results")).asList().hasSize(1);
        StubApiServer.Request req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.query()).isEqualTo("limit=50");
        assertThat(req.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void post_sendsJsonBody() {
        stub.onJson("/api/v1/tasks", 200, "{\"id\":\"6XGgmFVcrG5RRjVr\",\"content\":\"Buy milk\"}");

        Object task = client.post("/tasks", Map.of("content", "Buy milk"), TOKEN);

        assertThat(((Map<?, ?>) task).get("id")).isEqualTo("6XGgmFVcrG5RRjVr");
        StubApiServer.Request req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.header("Content-Type")).isEqualTo("application/json");
        assertThat(req.body()).isEqualTo("{\"content\":\"Buy milk\"}");
    }

    @Test
    void post_withoutBody_andNoContentAnswer_returnsNull() {
        stub.on("/api/v1/tasks/6XGgmFVcrG5RRjVr/close", new Response(204, null, Map.of()));

        assertThat(client.post("/tasks/6XGgmFVcrG5RRjVr/close", null, TOKEN)).isNull();
        assertThat(stub.lastRequest().body()).isEmpty();
    }

    @Test
    void unauthorized_mapsToTokenHint() {
        stub.onJson("/api/v1/user", 401, "{\"error\":\"Unauthorized\",\"error_tag\":\"AUTH_INVALID_TOKEN\",\"http_code\":401}");

        assertThatThrownBy(() -> client.get("/user", TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Todoist authentication failed — check the API token");
    }

    @Test
    void notFound_usesDocumentedJsonErrorFields() {
        stub.onJson("/api/v1/tasks/nope", 404, "{\"error\":\"Task not found\",\"error_code\":478,"
                + "\"error_extra\":{\"event_id\":\"abc\",\"retry_after\":3},\"error_tag\":\"NOT_FOUND\",\"http_code\":404}");

        assertThatThrownBy(() -> client.get("/tasks/nope", TOKEN))
                .hasMessage("Todoist API error 404: Task not found (NOT_FOUND)");
    }

    @Test
    void rateLimit_reportsRetryAfterFromErrorExtra() {
        stub.onJson("/api/v1/tasks", 429, "{\"error\":\"Too many requests\",\"error_extra\":{\"retry_after\":12},"
                + "\"error_tag\":\"LIMITS_REACHED\",\"http_code\":429}");

        assertThatThrownBy(() -> client.get("/tasks", TOKEN))
                .hasMessage("Todoist rate limit reached (HTTP 429) — retry after 12 s");
    }

    @Test
    void rateLimit_fallsBackToRetryAfterHeader() {
        stub.on("/api/v1/tasks", new Response(429, "", Map.of("Retry-After", "30")));

        assertThatThrownBy(() -> client.get("/tasks", TOKEN))
                .hasMessage("Todoist rate limit reached (HTTP 429) — retry after 30 s");
    }

    @Test
    void serverError_withNonJsonBody_carriesStatusOnly() {
        stub.on("/api/v1/tasks", new Response(503, "<html>Service Unavailable</html>", Map.of()));

        assertThatThrownBy(() -> client.get("/tasks", TOKEN))
                .hasMessage("Todoist API error 503");
    }

    @Test
    void connectionFailure_isWrappedWithoutToken() {
        TodoistHttpClient unreachable = new TodoistHttpClient(new ObjectMapper(), "http://127.0.0.1:1");

        assertThatThrownBy(() -> unreachable.get("/user", TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Todoist API request failed: ")
                .satisfies(e -> assertThat(e.toString()).doesNotContain(TOKEN))
                .hasNoCause();
    }

    @Test
    void scrub_removesTokenFromMessages() {
        assertThat(TodoistHttpClient.scrub("bad " + TOKEN + " here", TOKEN)).isEqualTo("bad *** here");
        assertThat(TodoistHttpClient.scrub(null, TOKEN)).isEqualTo("connection error");
    }
}
