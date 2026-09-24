package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.todoist.TodoistParams.*;

/**
 * Todoist integration provider — tasks, projects and comments via the unified Todoist API v1
 * ({@code /api/v1}; the former REST v2 is retired).
 *
 * <p>Authentication uses the personal API token (Todoist settings → Integrations → Developer),
 * not the OAuth2 flow ({@link #requiresOAuthFlow()} is {@code false}). The framework stores the
 * credential as {@code "<label>|<token>"}; the label is optional and informational.
 *
 * <p>Only simulated so far (no entry in {@code VerifiedProviders}): the tests run against a local
 * stub modelled on the official API reference.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoistProvider implements IntegrationProvider, TaskExportCapable {

    static final String TASK_URL = "https://app.todoist.com/app/task/";
    private static final int MAX_TARGET_PAGES = 5;

    private final ObjectMapper objectMapper;
    private final TodoistHttpClient http;

    @Override
    public String getProviderId() {
        return "todoist";
    }

    @Override
    public String getDisplayName() {
        return "Todoist";
    }

    @Override
    public String getIcon() {
        return "CheckSquare";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.todoist.description.
        return "Todoist-Aufgaben auflisten, anlegen, ändern, abhaken und kommentieren";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // Todoist offers OAuth, but this provider authenticates with the personal API token
        // (requiresOAuthFlow=false). Kept for completeness and the env-var fallback.
        return new OAuthConfig(
                "https://app.todoist.com/oauth/authorize",
                "https://api.todoist.com/oauth/access_token",
                List.of("data:read_write"),
                "TODOIST_CLIENT_ID",
                "TODOIST_CLIENT_SECRET"
        );
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    /** {@code GET /user}; keeps only name and e-mail — the response also echoes the API token. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            Object user = http.get("/user", extractToken(combinedToken));
            if (user instanceof Map<?, ?> u) {
                Map<String, Object> info = new LinkedHashMap<>();
                Object email = u.get("email");
                Object name = u.get("full_name");
                info.put("email", email != null ? email.toString() : "");
                info.put("name", name != null ? name.toString() : (email != null ? email.toString() : ""));
                return objectMapper.writeValueAsString(info);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Todoist account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return TodoistActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        String token = extractToken(accessToken);
        return switch (action) {
            case "list_projects" -> http.get("/projects" + page(params, new StringBuilder()), token);
            case "list_tasks" -> listTasks(params, token);
            case "get_task" -> http.get("/tasks/" + requireId(params, "task_id"), token);
            case "create_task" -> http.post("/tasks", createBody(params), token);
            case "update_task" -> updateTask(params, token);
            case "close_task" -> closeTask(params, token);
            case "add_comment" -> addComment(params, token);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Actions ---

    private Object listTasks(Map<String, Object> params, String token) {
        String filter = optionalString(params, "filter");
        String projectId = optionalString(params, "project_id");
        if (filter != null && projectId != null) {
            throw new IllegalArgumentException("Use either 'filter' or 'project_id', not both (a filter can name the project: '#Name')");
        }
        if (filter != null) {
            if (filter.length() > 1024) throw new IllegalArgumentException("Parameter 'filter' is longer than 1024 characters");
            StringBuilder query = new StringBuilder("?query=").append(urlEncode(filter));
            return http.get("/tasks/filter" + page(params, query), token);
        }
        StringBuilder query = new StringBuilder();
        if (projectId != null) query.append("?project_id=").append(urlEncode(id(projectId, "project_id")));
        return http.get("/tasks" + page(params, query), token);
    }

    private Map<String, Object> createBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", requireParam(params, "content"));
        putOptionalFields(params, body);
        String projectId = optionalString(params, "project_id");
        if (projectId != null) body.put("project_id", id(projectId, "project_id"));
        return body;
    }

    private Object updateTask(Map<String, Object> params, String token) {
        String taskId = requireId(params, "task_id");
        Map<String, Object> body = new LinkedHashMap<>();
        String content = optionalString(params, "content");
        if (content != null) body.put("content", content);
        putOptionalFields(params, body);
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Nothing to update: pass content, description, due_string, priority or labels");
        }
        return http.post("/tasks/" + taskId, body, token);
    }

    private Object closeTask(Map<String, Object> params, String token) {
        String taskId = requireId(params, "task_id");
        http.post("/tasks/" + taskId + "/close", null, token); // 204 No Content
        return Map.of("task_id", taskId, "closed", true);
    }

    private Object addComment(Map<String, Object> params, String token) {
        String taskId = requireId(params, "task_id");
        String content = requireParam(params, "content");
        if (content.length() > 15000) throw new IllegalArgumentException("Parameter 'content' is longer than 15000 characters");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("content", content);
        return http.post("/comments", body, token);
    }

    private static void putOptionalFields(Map<String, Object> params, Map<String, Object> body) {
        String description = optionalString(params, "description");
        if (description != null) body.put("description", description);
        String due = optionalString(params, "due_string");
        if (due != null) body.put("due_string", due);
        Integer priority = priority(params);
        if (priority != null) body.put("priority", priority);
        if (params.get("labels") != null) body.put("labels", labels(params.get("labels")));
    }

    /** Appends {@code limit} and an optional {@code cursor} to an (optionally started) query string. */
    private static String page(Map<String, Object> params, StringBuilder query) {
        query.append(query.isEmpty() ? '?' : '&').append("limit=").append(limit(params));
        String cursor = optionalString(params, "cursor");
        if (cursor != null) query.append("&cursor=").append(urlEncode(cursor));
        return query.toString();
    }

    // --- TaskExportCapable: target = project id ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        String projectId = id(input.target() == null ? null : input.target().trim(), "target");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", input.title() != null && !input.title().isBlank() ? input.title() : "TODO");
        if (input.body() != null && !input.body().isBlank()) body.put("description", input.body());
        if (input.labels() != null && !input.labels().isEmpty()) body.put("labels", labels(input.labels()));
        body.put("project_id", projectId);

        Object created = http.post("/tasks", body, extractToken(accessToken));
        if (!(created instanceof Map<?, ?> task) || task.get("id") == null) {
            throw new IllegalStateException("Todoist task creation returned no id");
        }
        String taskId = task.get("id").toString();
        return new TaskRef("todoist", taskId, TASK_URL + urlEncode(taskId));
    }

    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        String token = extractToken(accessToken);
        List<TaskTarget> out = new ArrayList<>();
        String cursor = null;
        for (int pageNo = 0; pageNo < MAX_TARGET_PAGES; pageNo++) {
            String path = "/projects?limit=" + MAX_LIMIT + (cursor != null ? "&cursor=" + urlEncode(cursor) : "");
            if (!(http.get(path, token) instanceof Map<?, ?> pageBody)) break;
            if (pageBody.get("results") instanceof List<?> results) {
                for (Object o : results) {
                    if (o instanceof Map<?, ?> project && project.get("id") != null) {
                        Object name = project.get("name");
                        String id = project.get("id").toString();
                        out.add(new TaskTarget(id, name != null ? name.toString() : id));
                    }
                }
            }
            Object next = pageBody.get("next_cursor");
            if (next == null || next.toString().isBlank()) break;
            cursor = next.toString();
        }
        return out;
    }
}
