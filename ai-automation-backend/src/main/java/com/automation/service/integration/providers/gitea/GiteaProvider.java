package com.automation.service.integration.providers.gitea;

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

import static com.automation.service.integration.providers.gitea.GiteaParams.*;

/**
 * Gitea / Forgejo integration provider — manages issues via the REST API ({@code /api/v1}).
 * Forgejo is a Gitea fork with the same API, so one provider serves both.
 *
 * <p>Direct token auth ({@link #requiresOAuthFlow()} = {@code false}): the connect form stores
 * {@code "<server url>|<access token>"}; {@link ServerTokenCredential} validates the URL. Every
 * call goes to that server only — there is no default instance.
 *
 * <p>Gitea expects label IDs, not names, when creating an issue or replacing its labels;
 * {@link GiteaLabels} resolves names against the repository's labels first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GiteaProvider implements IntegrationProvider, TaskExportCapable {

    private final ObjectMapper objectMapper;
    private final GiteaHttpClient http;

    @Override
    public String getProviderId() {
        return "gitea";
    }

    @Override
    public String getDisplayName() {
        return "Gitea / Forgejo";
    }

    @Override
    public String getIcon() {
        return "GitMerge";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers integrations.providers.gitea.description (DE/EN).
        return "Issues auf einem eigenen Gitea- oder Forgejo-Server auflisten, erstellen, aktualisieren und kommentieren";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // No OAuth flow: URLs/scopes are unused, the env-var slots name the two connect fields.
        return new OAuthConfig("", "", List.of(), "GITEA_URL", "GITEA_TOKEN");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            ServerTokenCredential cred = ServerTokenCredential.parse(combinedToken, null);
            Object user = http.get(api(cred) + "/user", cred.token(), Object.class);
            if (user instanceof Map<?, ?> u && u.get("login") != null) {
                String login = u.get("login").toString();
                Object fullName = u.get("full_name");
                String name = fullName != null && !fullName.toString().isBlank() ? fullName.toString() : login;
                return objectMapper.writeValueAsString(Map.of("login", login, "name", name, "server", cred.baseUrl()));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Gitea account info: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return GiteaActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        ServerTokenCredential cred = ServerTokenCredential.parse(accessToken, null);
        return switch (action) {
            case "list_repos" -> listRepos(params, cred);
            case "list_issues" -> listIssues(params, cred);
            case "get_issue" -> getIssue(params, cred);
            case "create_issue" -> createIssue(params, cred);
            case "update_issue" -> updateIssue(params, cred);
            case "add_comment" -> addComment(params, cred);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Actions ---

    private Object listRepos(Map<String, Object> params, ServerTokenCredential cred) {
        String url = api(cred) + "/user/repos?limit=" + limit(params, 30) + "&page=" + paramInt(params, "page", 1);
        return http.get(url, cred.token(), Object.class);
    }

    private Object listIssues(Map<String, Object> params, ServerTokenCredential cred) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        StringBuilder url = new StringBuilder(repoUrl(cred, repo))
                .append("/issues?type=issues&state=").append(urlEncode(paramString(params, "state", "open")))
                .append("&limit=").append(limit(params, 30))
                .append("&page=").append(paramInt(params, "page", 1));
        if (params.get("labels") != null) {
            url.append("&labels=").append(urlEncode(String.join(",", csv(params.get("labels")))));
        }
        if (params.get("since") != null) {
            url.append("&since=").append(urlEncode(params.get("since").toString()));
        }
        return http.get(url.toString(), cred.token(), Object.class);
    }

    private Object getIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        return http.get(issueUrl(cred, repo, requireNumber(params, "number")), cred.token(), Object.class);
    }

    private Object createIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        Map<String, Object> body = new HashMap<>();
        body.put("title", requireParam(params, "title"));
        if (params.get("body") != null) body.put("body", params.get("body").toString());
        if (params.get("assignees") != null) body.put("assignees", csv(params.get("assignees")));
        if (params.get("labels") != null) {
            body.put("labels", GiteaLabels.resolve(http, repoUrl(cred, repo), csv(params.get("labels")), cred.token(), true));
        }
        return http.post(repoUrl(cred, repo) + "/issues", body, cred.token(), Object.class);
    }

    private Object updateIssue(Map<String, Object> params, ServerTokenCredential cred) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String issueUrl = issueUrl(cred, repo, requireNumber(params, "number"));

        Map<String, Object> body = new HashMap<>();
        if (params.get("title") != null) body.put("title", params.get("title").toString());
        if (params.get("body") != null) body.put("body", params.get("body").toString());
        if (params.get("state") != null) body.put("state", state(params.get("state").toString()));
        if (params.get("assignees") != null) body.put("assignees", csv(params.get("assignees")));
        // Resolve labels before the first write, so an unknown name changes nothing.
        List<Long> labelIds = params.containsKey("labels")
                ? GiteaLabels.resolve(http, repoUrl(cred, repo), csv(params.get("labels")), cred.token(), true)
                : null;
        if (body.isEmpty() && labelIds == null) {
            throw new IllegalArgumentException("Nothing to update: pass title, body, state, labels or assignees");
        }

        Object result = body.isEmpty() ? null : http.patch(issueUrl, body, cred.token(), Object.class);
        if (labelIds != null) {
            // The edit endpoint has no labels field; labels are replaced through their own endpoint.
            http.put(issueUrl + "/labels", Map.of("labels", labelIds), cred.token(), Object.class);
            result = http.get(issueUrl, cred.token(), Object.class);
        }
        return result;
    }

    private Object addComment(Map<String, Object> params, ServerTokenCredential cred) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String url = issueUrl(cred, repo, requireNumber(params, "number")) + "/comments";
        return http.post(url, Map.of("body", requireParam(params, "body")), cred.token(), Object.class);
    }

    private static String state(String state) {
        String s = state.trim().toLowerCase(java.util.Locale.ROOT);
        if (!s.equals("open") && !s.equals("closed")) {
            throw new IllegalArgumentException("Parameter 'state' must be 'open' or 'closed'");
        }
        return s;
    }

    // --- TaskExportCapable ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        String[] repo = splitRepo(input.target()); // target = "owner/repo"; fails before any request
        ServerTokenCredential cred = ServerTokenCredential.parse(accessToken, null);
        Map<String, Object> body = new HashMap<>();
        body.put("title", input.title() != null && !input.title().isBlank() ? input.title() : "TODO");
        if (input.body() != null) body.put("body", input.body());
        if (input.labels() != null && !input.labels().isEmpty()) {
            List<Long> ids = GiteaLabels.resolve(http, repoUrl(cred, repo), input.labels(), cred.token(), false);
            if (!ids.isEmpty()) body.put("labels", ids);
        }
        Object created = http.post(repoUrl(cred, repo) + "/issues", body, cred.token(), Object.class);
        if (!(created instanceof Map<?, ?> issue) || !(issue.get("number") instanceof Number number)) {
            throw new IllegalStateException("Gitea issue creation returned no number");
        }
        Object htmlUrl = issue.get("html_url");
        return new TaskRef("gitea", repo[0] + "/" + repo[1] + "#" + number.longValue(),
                htmlUrl != null ? htmlUrl.toString() : null);
    }

    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        ServerTokenCredential cred = ServerTokenCredential.parse(accessToken, null);
        Object repos = http.get(api(cred) + "/user/repos?limit=" + MAX_LIMIT, cred.token(), Object.class);
        List<TaskTarget> out = new ArrayList<>();
        if (repos instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> r && r.get("full_name") != null) {
                    String fullName = r.get("full_name").toString();
                    out.add(new TaskTarget(fullName, fullName));
                }
            }
        }
        return out;
    }

    // --- URLs ---

    private static String api(ServerTokenCredential cred) {
        return cred.baseUrl() + "/api/v1";
    }

    private static String repoUrl(ServerTokenCredential cred, String[] repo) {
        return api(cred) + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1]);
    }

    private static String issueUrl(ServerTokenCredential cred, String[] repo, String number) {
        return repoUrl(cred, repo) + "/issues/" + number;
    }
}
