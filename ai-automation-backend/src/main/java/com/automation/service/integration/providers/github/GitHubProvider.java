package com.automation.service.integration.providers.github;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubProvider implements IntegrationProvider {

    private static final String BASE_URL = "https://api.github.com";
    private static final String API_VERSION = "2022-11-28";

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
            Object user = httpGet(BASE_URL + "/user", token, Object.class);
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

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("list_repos", "Zugängliche Repositories auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "perPage", Map.of("type", "integer", "default", 30, "description", "Max. Ergebnisse pro Seite"),
                                        "page", Map.of("type", "integer", "default", 1, "description", "Seitennummer")
                                )
                        )),
                new IntegrationAction("list_issues", "Issues eines Repositories auflisten",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "state", Map.of("type", "string", "default", "open", "description", "Status: open, closed, all"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)"),
                                        "since", Map.of("type", "string", "description", "Nur seit Zeitpunkt geänderte (ISO 8601)"),
                                        "perPage", Map.of("type", "integer", "default", 50, "description", "Max. Ergebnisse pro Seite"),
                                        "page", Map.of("type", "integer", "default", 1, "description", "Seitennummer")
                                ),
                                "required", List.of("repo")
                        )),
                new IntegrationAction("get_issue", "Ein einzelnes Issue abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer")
                                ),
                                "required", List.of("repo", "number")
                        )),
                new IntegrationAction("create_issue", "Ein neues Issue erstellen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "title", Map.of("type", "string", "description", "Titel des Issues"),
                                        "body", Map.of("type", "string", "description", "Beschreibung (Markdown)"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt)"),
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (Benutzernamen, kommagetrennt)")
                                ),
                                "required", List.of("repo", "title")
                        )),
                new IntegrationAction("update_issue", "Ein Issue aktualisieren (inkl. öffnen/schliessen)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer"),
                                        "title", Map.of("type", "string", "description", "Neuer Titel"),
                                        "body", Map.of("type", "string", "description", "Neue Beschreibung (Markdown)"),
                                        "state", Map.of("type", "string", "description", "Status: open, closed"),
                                        "labels", Map.of("type", "string", "description", "Labels (kommagetrennt, ersetzt bestehende)"),
                                        "assignees", Map.of("type", "string", "description", "Bearbeiter (kommagetrennt, ersetzt bestehende)")
                                ),
                                "required", List.of("repo", "number")
                        )),
                new IntegrationAction("add_comment", "Einen Kommentar zu einem Issue hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "repo", Map.of("type", "string", "description", "Repository als 'owner/repo'"),
                                        "number", Map.of("type", "integer", "description", "Issue-Nummer"),
                                        "body", Map.of("type", "string", "description", "Kommentartext (Markdown)")
                                ),
                                "required", List.of("repo", "number", "body")
                        ))
        );
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
        return httpGet(url, token, Object.class);
    }

    private Object listIssues(Map<String, Object> params, String token) {
        String[] repo = splitRepo(requireParam(params, "repo"));
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
        return httpGet(url.toString(), token, Object.class);
    }

    private Object getIssue(Map<String, Object> params, String token) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String number = requireParam(params, "number");
        return httpGet(issueUrl(repo, number), token, Object.class);
    }

    private Object createIssue(Map<String, Object> params, String token) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String title = requireParam(params, "title");

        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        if (params.containsKey("body")) body.put("body", params.get("body").toString());
        if (params.containsKey("labels")) body.put("labels", csv(params.get("labels")));
        if (params.containsKey("assignees")) body.put("assignees", csv(params.get("assignees")));

        String url = BASE_URL + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1]) + "/issues";
        return httpPost(url, body, token, Object.class);
    }

    private Object updateIssue(Map<String, Object> params, String token) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String number = requireParam(params, "number");

        Map<String, Object> body = new HashMap<>();
        if (params.containsKey("title")) body.put("title", params.get("title").toString());
        if (params.containsKey("body")) body.put("body", params.get("body").toString());
        if (params.containsKey("state")) body.put("state", params.get("state").toString());
        if (params.containsKey("labels")) body.put("labels", csv(params.get("labels")));
        if (params.containsKey("assignees")) body.put("assignees", csv(params.get("assignees")));

        return httpPatch(issueUrl(repo, number), body, token, Object.class);
    }

    private Object addComment(Map<String, Object> params, String token) {
        String[] repo = splitRepo(requireParam(params, "repo"));
        String number = requireParam(params, "number");
        String comment = requireParam(params, "body");
        String url = issueUrl(repo, number) + "/comments";
        return httpPost(url, Map.of("body", comment), token, Object.class);
    }

    private String issueUrl(String[] repo, String number) {
        return BASE_URL + "/repos/" + urlEncode(repo[0]) + "/" + urlEncode(repo[1])
                + "/issues/" + urlEncode(number);
    }

    // --- HTTP helpers ---

    private <T> T httpGet(String url, String token, Class<T> responseType) {
        return send(requestBuilder(url, token).GET().build(), responseType);
    }

    private <T> T httpPost(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    private <T> T httpPatch(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    private HttpRequest.Builder requestBuilder(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GitHub API request failed: " + e.getMessage(), e);
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            throw new IllegalStateException("GitHub authentication failed — check the Personal Access Token");
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("GitHub API error " + response.statusCode() + ": " + response.body());
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GitHub request body: " + e.getMessage(), e);
        }
    }

    // --- Parameter helpers ---

    /** Extracts the PAT from the framework's "username|token" credential format. */
    private static String extractToken(String combined) {
        if (combined == null) return null;
        int idx = combined.indexOf('|');
        return idx >= 0 ? combined.substring(idx + 1) : combined;
    }

    private static String[] splitRepo(String repo) {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Parameter 'repo' must be in the form 'owner/repo'");
        }
        return parts;
    }

    private static List<String> csv(Object value) {
        List<String> result = new ArrayList<>();
        for (String s : Arrays.asList(value.toString().split(","))) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) return Integer.parseInt(s);
        return defaultValue;
    }

    private static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
