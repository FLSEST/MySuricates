package com.automation.service.integration.providers.gitlab;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.ServerTokenCredential;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.gitlab.GitLabParams.*;

/**
 * GitLab integration provider (gitlab.com or self-managed) — manages issues via the REST API v4.
 *
 * <p>Direct token auth ({@link #requiresOAuthFlow()} = {@code false}): the connect form stores
 * {@code "<server url>|<access token>"}; an empty URL means {@value #DEFAULT_URL}. Personal, project
 * and group access tokens all work; the {@code PRIVATE-TOKEN} header carries them.
 *
 * <p>Projects are addressed by path ({@code group/sub/project}, URL-encoded to one path segment) or
 * numeric ID; issues by their project-local IID.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitLabProvider implements IntegrationProvider, TaskExportCapable {

    static final String DEFAULT_URL = "https://gitlab.com";

    private final ObjectMapper objectMapper;
    private final GitLabHttpClient http;

    @Override
    public String getProviderId() {
        return "gitlab";
    }

    @Override
    public String getDisplayName() {
        return "GitLab";
    }

    @Override
    public String getIcon() {
        return "GitPullRequest";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers integrations.providers.gitlab.description (DE/EN).
        return "GitLab-Issues (gitlab.com oder eigene Instanz) auflisten, erstellen, aktualisieren und kommentieren";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // No OAuth flow: URLs/scopes are unused, the env-var slots name the two connect fields.
        return new OAuthConfig("", "", List.of(), "GITLAB_URL", "GITLAB_TOKEN");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            ServerTokenCredential cred = credential(combinedToken);
            Object user = http.get(api(cred) + "/user", cred.token(), Object.class);
            if (user instanceof Map<?, ?> u && u.get("username") != null) {
                String username = u.get("username").toString();
                Object name = u.get("name");
                Map<String, Object> info = new HashMap<>();
                info.put("login", username);
                info.put("name", name != null && !name.toString().isBlank() ? name.toString() : username);
                info.put("server", cred.baseUrl());
                if (u.get("email") instanceof String email && !email.isBlank()) info.put("email", email);
                return objectMapper.writeValueAsString(info);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch GitLab account info: {}", e.getMessage());
        }
        return null;
    }

    /** Scopes of the token itself ({@code GET /personal_access_tokens/self}, GitLab 15.5+); {@code null} if unknown. */
    @Override
    public String fetchGrantedScopes(String combinedToken) {
        try {
            ServerTokenCredential cred = credential(combinedToken);
            Object self = http.get(api(cred) + "/personal_access_tokens/self", cred.token(), Object.class);
            if (self instanceof Map<?, ?> t && t.get("scopes") instanceof List<?> scopes && !scopes.isEmpty()) {
                return String.join(" ", scopes.stream().map(Object::toString).toList());
            }
        } catch (Exception e) {
            log.warn("Failed to read GitLab token scopes: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return GitLabActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        ServerTokenCredential cred = credential(accessToken);
        return switch (action) {
            case "list_projects" -> listProjects(params, cred);
            case "list_issues" -> listIssues(params, cred);
            case "get_issue" -> getIssue(params, cred);
            case "create_issue" -> createIssue(params, cred);
            case "update_issue" -> updateIssue(params, cred);
            case "add_comment" -> addComment(params, cred);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Actions ---

    private Object listProjects(Map<String, Object> params, ServerTokenCredential cred) {
        StringBuilder url = new StringBuilder(api(cred))
                .append("/projects?membership=true&simple=true&order_by=last_activity_at")
                .append("&per_page=").append(perPage(params, 30))
                .append("&page=").append(paramInt(params, "page", 1));
        if (params.get("search") != null) url.append("&search=").append(urlEncode(params.get("search").toString()));
        return http.get(url.toString(), cred.token(), Object.class);
    }

    private Object listIssues(Map<String, Object> params, ServerTokenCredential cred) {
        String project = projectRef(requireParam(params, "project"));
        StringBuilder url = new StringBuilder(api(cred)).append("/projects/").append(project).append("/issues?")
                .append("per_page=").append(perPage(params, 30))
                .append("&page=").append(paramInt(params, "page", 1));
        String state = listState(paramString(params, "state", "open"));
        if (state != null) url.append("&state=").append(state);
        if (params.get("labels") != null) {
            url.append("&labels=").append(urlEncode(String.join(",", csv(params.get("labels")))));
        }
        if (params.get("since") != null) url.append("&updated_after=").append(urlEncode(params.get("since").toString()));
        return http.get(url.toString(), cred.token(), Object.class);
    }

    private Object getIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String project = projectRef(requireParam(params, "project"));
        return http.get(issueUrl(cred, project, requireIid(params, "number")), cred.token(), Object.class);
    }

    private Object createIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String project = projectRef(requireParam(params, "project"));
        Map<String, Object> body = new HashMap<>();
        body.put("title", requireParam(params, "title"));
        if (params.get("body") != null) body.put("description", params.get("body").toString());
        if (params.get("labels") != null) body.put("labels", String.join(",", csv(params.get("labels"))));
        if (params.get("assigneeIds") != null) body.put("assignee_ids", ids(params.get("assigneeIds"), "assigneeIds"));
        return http.post(api(cred) + "/projects/" + project + "/issues", body, cred.token(), Object.class);
    }

    private Object updateIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String project = projectRef(requireParam(params, "project"));
        String iid = requireIid(params, "number");
        Map<String, Object> body = new HashMap<>();
        if (params.get("title") != null) body.put("title", params.get("title").toString());
        if (params.get("body") != null) body.put("description", params.get("body").toString());
        if (params.get("state") != null) body.put("state_event", stateEvent(params.get("state").toString()));
        // An empty string clears all labels — explicit, like GitLab itself.
        if (params.containsKey("labels")) body.put("labels", String.join(",", csv(params.get("labels"))));
        if (params.get("assigneeIds") != null) body.put("assignee_ids", ids(params.get("assigneeIds"), "assigneeIds"));
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Nothing to update: pass title, body, state, labels or assigneeIds");
        }
        return http.put(issueUrl(cred, project, iid), body, cred.token(), Object.class);
    }

    private Object addComment(Map<String, Object> params, ServerTokenCredential cred) {
        String project = projectRef(requireParam(params, "project"));
        String url = issueUrl(cred, project, requireIid(params, "number")) + "/notes";
        return http.post(url, Map.of("body", requireParam(params, "body")), cred.token(), Object.class);
    }

    // --- TaskExportCapable ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        String project = projectRef(input.target()); // target = "group/project" or ID; fails before any request
        ServerTokenCredential cred = credential(accessToken);
        Map<String, Object> body = new HashMap<>();
        body.put("title", input.title() != null && !input.title().isBlank() ? input.title() : "TODO");
        if (input.body() != null) body.put("description", input.body());
        if (input.labels() != null && !input.labels().isEmpty()) body.put("labels", String.join(",", input.labels()));

        Object created = http.post(api(cred) + "/projects/" + project + "/issues", body, cred.token(), Object.class);
        if (!(created instanceof Map<?, ?> issue) || !(issue.get("iid") instanceof Number iid)) {
            throw new IllegalStateException("GitLab issue creation returned no iid");
        }
        Object webUrl = issue.get("web_url");
        return new TaskRef("gitlab", input.target().trim() + "#" + iid.longValue(), webUrl != null ? webUrl.toString() : null);
    }

    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        ServerTokenCredential cred = credential(accessToken);
        Object projects = http.get(api(cred) + "/projects?membership=true&simple=true&order_by=last_activity_at&per_page="
                + MAX_PER_PAGE, cred.token(), Object.class);
        List<TaskTarget> out = new ArrayList<>();
        if (projects instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> p && p.get("path_with_namespace") != null) {
                    String path = p.get("path_with_namespace").toString();
                    Object name = p.get("name_with_namespace");
                    out.add(new TaskTarget(path, name != null ? name.toString() : path));
                }
            }
        }
        return out;
    }

    // --- Helpers ---

    private static ServerTokenCredential credential(String combined) {
        return ServerTokenCredential.parse(combined, DEFAULT_URL);
    }

    private static String api(ServerTokenCredential cred) {
        return cred.baseUrl() + "/api/v4";
    }

    private static String issueUrl(ServerTokenCredential cred, String project, String iid) {
        return api(cred) + "/projects/" + project + "/issues/" + iid;
    }
}
