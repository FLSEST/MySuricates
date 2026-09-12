package com.automation.service.integration.providers.github;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.github.GitHubParams.*;

/**
 * GitHub integration provider — manages GitHub Issues via the REST API.
 *
 * <p>Authentication uses a Personal Access Token (PAT), not the OAuth2 flow
 * ({@link #requiresOAuthFlow()} returns {@code false}). The credential is stored
 * by the framework as {@code "<username>|<PAT>"}; only the token part is used for
 * API calls, the username part is optional and purely informational.
 *
 * <p>This provider is generic and reusable for any GitHub repository: every action
 * takes a {@code repo} parameter in {@code "owner/repo"} form, so a single provider
 * + sync workflow serves arbitrarily many projects/repositories.
 *
 * <p>Because the PAT may grant access far beyond the target project, every
 * repo-scoped action is validated against the {@code github.repo-allowlist}
 * app-setting (comma-separated {@code owner/repo} entries, {@code owner/*}
 * allows a whole owner). An empty/unset allowlist keeps the legacy allow-all
 * behavior but logs a warning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubProvider implements IntegrationProvider, TaskExportCapable {

    private static final String BASE_URL = "https://api.github.com";
    private static final String ALLOWLIST_KEY = "github.repo-allowlist";

    private final ObjectMapper objectMapper;
    private final AppSettingsRepository appSettingsRepository;
    private final GitHubHttpClient http;
    private volatile boolean warnedNoAllowlist;

    @Override
    public String getProviderId() {
        return "github";
    }

    @Override
    public String getDisplayName() {
        return "GitHub";
    }

    @Override
    public String getIcon() {
        return "Github";
    }

    @Override
    public String getDescription() {
        // Fallback description (German). The UI prefers the localized copy under
        // i18n key integrations.providers.github.description (DE/EN).
        return "GitHub-Issues synchronisieren: auflisten, erstellen, aktualisieren und kommentieren";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // GitHub does offer OAuth, but this provider authenticates via PAT
        // (requiresOAuthFlow=false). The config is kept for completeness and for
        // the env-var fallback in IntegrationOAuthService.
        return new OAuthConfig(
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                List.of("repo"),
                "GITHUB_CLIENT_ID",
                "GITHUB_CLIENT_SECRET"
        );
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            String token = extractToken(combinedToken);
            Object user = http.get(BASE_URL + "/user", token, Object.class);
            if (user instanceof Map<?, ?> u) {
                Object login = u.get("login");
                Object name = u.get("name");
                return objectMapper.writeValueAsString(Map.of(
                        "login", login != null ? login : "",
                        "name", name != null ? name : (login != null ? login : "")
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch GitHub account info: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Classic PATs report their scopes in the {@code X-OAuth-Scopes} header of any API response;
     * fine-grained PATs send none, which we surface as {@code null} (unknown), never as "no scopes".
     */
    @Override
    public String fetchGrantedScopes(String combinedToken) {
        try {
            HttpResponse<String> response = http.getRaw(BASE_URL + "/user", extractToken(combinedToken));
            return parseScopeHeader(response.headers().firstValue("X-OAuth-Scopes").orElse(null));
        } catch (Exception e) {
            log.warn("Failed to read GitHub token scopes: {}", e.getMessage());
            return null;
        }
    }

    /** "repo, read:org" → "repo read:org"; blank/absent → null (unknown). */
    static String parseScopeHeader(String header) {
        if (header == null || header.isBlank()) return null;
        String joined = String.join(" ", header.split("\\s*,\\s*")).trim();
        return joined.isEmpty() ? null : joined;
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return GitHubActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        String token = extractToken(accessToken);
        return switch (action) {
            case "list_repos" -> listRepos(params, token);
            case "list_issues" -> listIssues(params, token);
            case "get_issue" -> getIssue(params, token);
            case "create_issue" -> createIssue(params, token);
            case "update_issue" -> updateIssue(params, token);
            case "add_comment" -> addComment(params, token);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Actions ---

    private Object listRepos(Map<String, Object> params, String token) {
        int perPage = paramInt(params, "perPage", 30);
        int page = paramInt(params, "page", 1);
        String url = BASE_URL + "/user/repos?per_page=" + perPage + "&page=" + page + "&sort=updated";
        return http.get(url, token, Object.class);
    }

    private Object listIssues(Map<String, Object> params, String token) {
        String[] repo = requireAllowedRepo(params);
        int perPage = paramInt(params, "perPage", 50);
        int page = paramInt(params, "page", 1);
        String state = paramString(params, "state", "open");

        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/repos/").append(urlEncode(repo[0])).append("/").append(urlEncode(repo[1]))
                .append("/issues?state=").append(urlEncode(state))
                .append("&per_page=").append(perPage)
                .append("&page=").append(page);
        if (params.containsKey("labels")) {
            url.append("&labels=").append(urlEncode(params.get("labels").toString()));
        }
        if (params.containsKey("since")) {
            url.append("&since=").append(urlEncode(params.get("since").toString()));
        }
        return http.get(url.toString(), token, Object.class);
    }

    private Object getIssue(Map<String, Object> params, String token) {
        String[] repo = requireAllowedRepo(params);
        String number = requireParam(params, "number");
        return http.get(issueUrl(repo, number), token, Object.class);
    }

    private Object createIssue(Map<String, Object> params, String token) {
        String[] repo = requireAllowedRepo(params);
        String title = requireParam(params, "title");

        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        if (params.containsKey("body")) body.put("body", params.get("body").toString());
        if (params.containsKey("labels")) body.put("labels", csv(params.get("labels")));
        if (params.containsKey("assignees")) body.put("assignees", csv(params.get("assignees")));

        String url = BASE_URL + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1]) + "/issues";
        return http.post(url, body, token, Object.class);
    }

    private Object updateIssue(Map<String, Object> params, String token) {
        String[] repo = requireAllowedRepo(params);
        String number = requireParam(params, "number");

        Map<String, Object> body = new HashMap<>();
        if (params.containsKey("title")) body.put("title", params.get("title").toString());
        if (params.containsKey("body")) body.put("body", params.get("body").toString());
        if (params.containsKey("state")) body.put("state", params.get("state").toString());
        if (params.containsKey("labels")) body.put("labels", csv(params.get("labels")));
        if (params.containsKey("assignees")) body.put("assignees", csv(params.get("assignees")));

        return http.patch(issueUrl(repo, number), body, token, Object.class);
    }

    private Object addComment(Map<String, Object> params, String token) {
        String[] repo = requireAllowedRepo(params);
        String number = requireParam(params, "number");
        String comment = requireParam(params, "body");
        String url = issueUrl(repo, number) + "/comments";
        return http.post(url, Map.of("body", comment), token, Object.class);
    }

    private String issueUrl(String[] repo, String number) {
        return BASE_URL + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1])
                + "/issues/" + urlEncode(number);
    }

    // --- TaskExportCapable (integrations-agnostischer Single-TODO-Export) ---

    @Override
    public TaskRef createTask(TaskExportInput input, String accessToken) {
        String token = extractToken(accessToken);
        String[] repo = allowedRepo(input.target()); // target = "owner/repo"
        Map<String, Object> body = new HashMap<>();
        body.put("title", input.title() != null && !input.title().isBlank() ? input.title() : "TODO");
        if (input.body() != null) body.put("body", input.body());
        if (input.labels() != null && !input.labels().isEmpty()) body.put("labels", input.labels());

        String url = BASE_URL + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1]) + "/issues";
        Object created = http.post(url, body, token, Object.class);
        if (!(created instanceof Map<?, ?> issue) || issue.get("number") == null) {
            throw new IllegalStateException("GitHub issue creation returned no number");
        }
        Object number = issue.get("number");
        String num = number instanceof Number n ? String.valueOf(n.longValue()) : number.toString();
        Object htmlUrl = issue.get("html_url");
        return new TaskRef("github", input.target() + "#" + num, htmlUrl != null ? htmlUrl.toString() : null);
    }

    @Override
    public List<TaskTarget> listTargets(String accessToken) {
        String token = extractToken(accessToken);
        Object repos = http.get(BASE_URL + "/user/repos?per_page=100&sort=updated", token, Object.class);
        List<TaskTarget> out = new ArrayList<>();
        if (repos instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> repo) {
                    Object fullName = repo.get("full_name");
                    if (fullName != null) out.add(new TaskTarget(fullName.toString(), fullName.toString()));
                }
            }
        }
        return out;
    }

    // --- Repo allowlist ---

    private String[] requireAllowedRepo(Map<String, Object> params) {
        return allowedRepo(requireParam(params, "repo"));
    }

    /** Splits {@code owner/repo} and enforces the {@code github.repo-allowlist} setting. */
    private String[] allowedRepo(String repoParam) {
        String[] repo = splitRepo(repoParam);
        List<String> allowlist = appSettingsRepository.get(ALLOWLIST_KEY)
                .map(GitHubParams::csv)
                .orElse(List.of());
        if (allowlist.isEmpty()) {
            if (!warnedNoAllowlist) {
                warnedNoAllowlist = true;
                log.warn("No GitHub repo allowlist configured ({}) — actions may target any repository the PAT can access", ALLOWLIST_KEY);
            }
            return repo;
        }
        String full = (repo[0] + "/" + repo[1]).toLowerCase(java.util.Locale.ROOT);
        String ownerWildcard = repo[0].toLowerCase(java.util.Locale.ROOT) + "/*";
        boolean allowed = allowlist.stream()
                .map(e -> e.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(e -> e.equals(full) || e.equals(ownerWildcard));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Repository '" + repo[0] + "/" + repo[1] + "' is not covered by the '" + ALLOWLIST_KEY + "' setting");
        }
        return repo;
    }
}
